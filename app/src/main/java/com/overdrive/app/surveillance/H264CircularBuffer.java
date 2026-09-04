package com.overdrive.app.surveillance;

import android.media.MediaCodec;
import com.overdrive.app.logging.DaemonLogger;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * H264CircularBuffer - SOTA Zero-Allocation Edition.
 * 
 * Fixes video stutter by pooling ByteBuffers.
 * Eliminates 'ByteBuffer.allocateDirect' calls during recording.
 * 
 * Key optimizations:
 * - Pre-allocated buffer pool (no runtime allocations)
 * - Object recycling (zero GC pressure)
 * - Keyframe-aligned pruning (valid MP4 generation)
 * - Thread-safe operations
 */
public class H264CircularBuffer {
    private static final String TAG = "H264CircularBuffer";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);
    
    // MAX_PACKET_SIZE is now per-instance, derived from the configured
    // bitrate at construction time. The previous hardcoded 256 KB was
    // sized for 6 Mbps H.264 at 1080p; at MAX H.265 (10 Mbps) on the
    // 2560×1920 mosaic, startup-mode I-frames routinely run 400–550 KB
    // and were being silently dropped — leaving the pre-record window
    // with zero keyframes so getPacketsForFlush() returned empty on
    // motion events. See computeMaxPacketSize() for the sizing formula.
    //
    // Floor and ceiling clamp the per-instance value:
    //   - Floor (64 KB) covers ECONOMY 1 Mbps without underflowing on
    //     occasional VBR spikes.
    //   - Ceiling (1 MB) bounds peak gralloc/heap pressure even if a
    //     future codec setting tries to crank bitrate beyond hardware
    //     capability. At pool-200 capacity that's a 200 MB worst-case
    //     footprint — uncomfortable but not OOM, and the actual pool
    //     sizing (duration × fps × 1.25, clamped to 200) keeps real
    //     usage far smaller.
    // Floor raised to 512 KB — field logs at 2 Mbps H.265 / 2560×1920
    // routinely produced IDRs at 270–346 KB, with the encoder going
    // "hotter" up to ~350 KB during high-detail scenes. The previous
    // 128 KB floor + 1-second-of-bandwidth formula gave a 250 KB cap at
    // 2 Mbps, which silently dropped EVERY keyframe (buffer state went
    // to "0 keyframes" steady-state, leaving recordings with empty
    // pre-record windows). 512 KB covers all observed I-frame sizes at
    // 2-3 Mbps with headroom; higher tiers fall through to the formula.
    private static final int MIN_PACKET_SIZE_FLOOR  = 512 * 1024;
    private static final int MAX_PACKET_SIZE_CEILING = 1024 * 1024;
    /** Default bitrate for the legacy 1-arg / 2-arg ctors that don't pass one. */
    private static final int DEFAULT_BITRATE_HINT = 6_000_000;
    
    // POOL CEILING: hard cap to bound peak memory regardless of fps. With
    // 30 fps × 5 s × 256KB = 38 MB worst-case, we allow up to 200 packets
    // (~50 MB peak). Pool sizing inside the ctor uses configured fps and
    // adds 25% headroom; this constant only kicks in if duration × fps
    // somehow exceeds the cap.
    private static final int POOL_CAPACITY = 200;
    /** Default fps used when caller doesn't specify. Conservative. */
    private static final int DEFAULT_FPS_HINT = 15;
    
    /**
     * Mutable Packet wrapper (reusable).
     */
    public static class Packet {
        public ByteBuffer data;  // Reusable container
        public final MediaCodec.BufferInfo info;
        public boolean isKeyFrame;
        
        public Packet(int capacity) {
            // Allocate ONCE during init
            this.data = ByteBuffer.allocateDirect(capacity);
            this.info = new MediaCodec.BufferInfo();
        }
        
        /**
         * Copies the source bytes into the pooled direct buffer.
         *
         * @return {@code true} on success, {@code false} if the source is
         *         larger than this packet's pre-allocated capacity (caller
         *         MUST drop the packet — we never grow the buffer at
         *         runtime since the discarded direct buffer would only be
         *         reclaimed via the Cleaner / finalizer, leaking native
         *         heap until then).
         */
        public boolean copyFrom(ByteBuffer src, MediaCodec.BufferInfo srcInfo) {
            if (this.data.capacity() < srcInfo.size) {
                // Oversized I-frame spike. Drop rather than reallocate: the
                // old direct buffer would otherwise wait for the Cleaner.
                return false;
            }
            this.info.set(0, srcInfo.size, srcInfo.presentationTimeUs, srcInfo.flags);
            this.isKeyFrame = (srcInfo.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;

            this.data.clear();
            src.position(srcInfo.offset);
            src.limit(srcInfo.offset + srcInfo.size);
            this.data.put(src);
            this.data.flip();
            return true;
        }
    }
    
    // The active ring buffer
    private final ConcurrentLinkedDeque<Packet> buffer = new ConcurrentLinkedDeque<>();
    
    // The Object Pool (Recycler)
    private final ArrayBlockingQueue<Packet> pool;
    
    private final long maxDurationUs;
    // FPS the pool was sized for — packet capacity = duration × fps × 1.25.
    // Exposed via getSizedForFps() so the encoder reuse path can detect a
    // mismatch (e.g., 15-fps-sized pool reused at 30 fps would only hold
    // ~3 s of pre-record footage, not the configured 5 s).
    private final int sizedForFps;
    // Bitrate (bps) the per-packet ceiling was sized for. Exposed via
    // getSizedForBitrate() so the encoder reuse path detects a quality
    // change (e.g. STANDARD 3 Mbps → MAX 10 Mbps) and reallocates with
    // a larger ceiling instead of silently dropping I-frames.
    private final int sizedForBitrate;
    // Per-packet ceiling actually used by this instance. Bigger pools cost
    // more native memory; smaller ones drop oversized I-frames silently.
    // Computed from bitrate at construction time, clamped to
    // [MIN_PACKET_SIZE_FLOOR, MAX_PACKET_SIZE_CEILING].
    private final int maxPacketSize;
    private long currentDurationUs = 0;
    private int keyframeCount = 0;
    private int addCount = 0;  // Debug: track total adds
    private final int minKeyframes;  // Minimum keyframes to keep based on duration

    /**
     * Creates a circular buffer sized for the configured pre-record window.
     * Uses default fps and bitrate hints. Prefer the 3-arg ctor so the
     * per-packet ceiling is correct for the actual encoder bitrate.
     */
    public H264CircularBuffer(int durationSeconds) {
        this(durationSeconds, DEFAULT_FPS_HINT, DEFAULT_BITRATE_HINT);
    }

    /** Legacy 2-arg ctor — uses a default bitrate hint. */
    public H264CircularBuffer(int durationSeconds, int fps) {
        this(durationSeconds, fps, DEFAULT_BITRATE_HINT);
    }

    /**
     * Creates a circular buffer sized for {@code durationSeconds × fps}
     * packets plus 25% headroom (capped at POOL_CAPACITY) with a per-packet
     * ceiling derived from the configured bitrate.
     *
     * @param durationSeconds Buffer duration in seconds (e.g., 5)
     * @param fps             Encoder fps used to size the pool. Pass the
     *                        encoder's KEY_FRAME_RATE so 30 fps recordings
     *                        don't exhaust the pool.
     * @param bitrate         Encoder KEY_BIT_RATE (bps). Used to size each
     *                        packet's pre-allocated direct ByteBuffer so
     *                        startup-mode I-frames at high quality don't
     *                        get silently dropped.
     */
    public H264CircularBuffer(int durationSeconds, int fps, int bitrate) {
        this.maxDurationUs = durationSeconds * 1_000_000L;

        // Calculate minimum keyframes needed based on duration. With 2-second
        // I-frame interval, we need (duration / 2) + 1 keyframes; +1 margin.
        this.minKeyframes = (durationSeconds / 2) + 2;

        int safeFps = Math.max(10, Math.min(30, fps));
        int safeBitrate = Math.max(500_000, bitrate);  // floor at 0.5 Mbps to avoid math weirdness
        this.sizedForFps = safeFps;
        this.sizedForBitrate = safeBitrate;
        this.maxPacketSize = computeMaxPacketSize(safeBitrate);

        // Pool size = duration × fps × 1.25, clamped to POOL_CAPACITY.
        int estimatedPackets = durationSeconds * safeFps;
        int poolSize = Math.min(estimatedPackets + (estimatedPackets / 4), POOL_CAPACITY);

        logger.info("Pre-allocating circular buffer pool (" + poolSize + " packets × "
                + (maxPacketSize / 1024) + "KB = "
                + (poolSize * (long) maxPacketSize / 1024 / 1024) + "MB) for "
                + durationSeconds + "s @ " + safeFps + "fps @ "
                + (safeBitrate / 1_000_000) + "Mbps...");
        pool = new ArrayBlockingQueue<>(poolSize);
        for (int i = 0; i < poolSize; i++) {
            pool.offer(new Packet(maxPacketSize));
        }
        logger.info("Buffer pool ready (" + durationSeconds + "s, minKeyframes="
                + minKeyframes + ", maxPacket=" + (maxPacketSize / 1024)
                + "KB). Zero-allocation mode active.");
    }

    /**
     * Per-packet ceiling formula. ~3 seconds of bandwidth ÷ 8 bits/byte.
     *
     * I-frames at the start of a GOP are much larger than the average
     * per-frame budget — at 2560×1920 H.265 + 2-second GOP, observed IDR
     * sizes are 4-12× the average frame budget. The previous "1 second of
     * bandwidth" formula gave a 250 KB cap at 2 Mbps which silently dropped
     * 270-346 KB I-frames every time (field log: 50+ keyframe drops/min,
     * buffer state stuck at "0 keyframes" → empty pre-record window).
     *
     * 3 seconds of bandwidth covers worst-case startup-mode IDRs + forced
     * rotation keyframes with margin. Floor is 512 KB so even at 1 Mbps
     * the 200-300 KB IDRs fit.
     *
     * Examples:
     *   ECONOMY  1 Mbps → 384 KB → clamped to 512 KB floor
     *   2 Mbps          → 750 KB
     *   STANDARD 3 Mbps → 1024 KB → clamped to ceiling
     *   HIGH     6 Mbps → 1024 KB → clamped
     *   PREMIUM 10 Mbps → 1024 KB → clamped
     */
    private static int computeMaxPacketSize(int bitrate) {
        long bytesPerSec = (long) bitrate / 8L;
        long perPacket = bytesPerSec * 3L;
        if (perPacket < MIN_PACKET_SIZE_FLOOR)   return MIN_PACKET_SIZE_FLOOR;
        if (perPacket > MAX_PACKET_SIZE_CEILING) return MAX_PACKET_SIZE_CEILING;
        return (int) perPacket;
    }

    /**
     * Adds a packet to the buffer using pooled allocation.
     * 
     * @param data Encoded H.264 data
     * @param info Buffer metadata
     */
    public synchronized void add(ByteBuffer data, MediaCodec.BufferInfo info) {
        // Borrow a packet from the pool (Instant - no allocation)
        Packet packet = pool.poll();

        if (packet == null) {
            // Pool empty? We are generating frames faster than pruning.
            // Force prune to recycle an old packet.
            if (!buffer.isEmpty()) {
                recyclePacket(buffer.removeFirst());
                packet = pool.poll();
            }

            // Still null? Drop this frame rather than emergency-allocating
            // a fresh direct ByteBuffer. An emergency packet can't be safely
            // returned to a full pool (recyclePacket would have to drop it,
            // leaking the direct buffer until the Cleaner runs). Pool
            // sizing already accounts for duration × fps × 1.25; sustained
            // exhaustion means the encoder is mis-paced, not a transient.
            if (packet == null) {
                logger.warn("Pool exhausted - dropping packet (size=" + info.size
                        + ", flags=" + info.flags + ")");
                return;
            }
        }

        // Copy data (Fast memcpy, no allocation). Returns false if the source
        // is larger than the per-instance maxPacketSize — drop and recycle.
        if (!packet.copyFrom(data, info)) {
            boolean wasKey = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
            logger.warn("Dropping oversized packet (size=" + info.size
                    + " > " + maxPacketSize + (wasKey ? ", KEYFRAME" : "")
                    + ") — buffer was sized for " + (sizedForBitrate / 1_000_000)
                    + "Mbps; encoder may be running hotter");
            pool.offer(packet);  // Pool slot still owned by us; return it.
            return;
        }
        buffer.addLast(packet);

        if (packet.isKeyFrame) {
            keyframeCount++;
        }

        addCount++;
        
        // Update duration
        if (buffer.size() > 1) {
            currentDurationUs = buffer.getLast().info.presentationTimeUs - 
                              buffer.getFirst().info.presentationTimeUs;
        }
        
        // Debug: Log buffer state every 50 frames (~6 seconds at 8 FPS)
        if (addCount % 50 == 0) {
            logger.debug(String.format("Buffer state: %d packets, %.1f sec, %d keyframes, pool=%d free",
                    buffer.size(), currentDurationUs / 1_000_000.0, keyframeCount, pool.size()));
        }
        
        // Prune and Recycle old packets
        pruneOldPackets();
    }
    
    /**
     * Recycles a packet back to the pool. Pool capacity equals the number of
     * packets ever allocated (we never allocate beyond pool size; oversize /
     * exhaustion paths drop instead — see {@link #add}). offer() therefore
     * always succeeds.
     *
     * @param p Packet to recycle
     */
    private void recyclePacket(Packet p) {
        if (p != null) {
            if (p.isKeyFrame) {
                keyframeCount--;
            }
            p.data.clear();
            pool.offer(p);
        }
    }
    
    /**
     * Prunes old packets to maintain buffer duration limit.
     * 
     * CRITICAL: Keeps enough keyframes to maintain target duration.
     * minKeyframes is calculated based on configured pre-record duration.
     */
    private void pruneOldPackets() {
        while (currentDurationUs > maxDurationUs && buffer.size() > 1) {
            Packet first = buffer.getFirst();
            
            // Don't prune if we'd drop below minimum keyframes
            if (first.isKeyFrame && keyframeCount <= minKeyframes) {
                break;  // Keep this keyframe, we're at minimum
            }
            
            // Find the next keyframe in the buffer
            Packet nextKeyframe = null;
            for (Packet p : buffer) {
                if (p.isKeyFrame && p != first) {
                    nextKeyframe = p;
                    break;
                }
            }
            
            // Logic to keep Keyframe alignment
            if (first.isKeyFrame && nextKeyframe != null) {
                // Safe to remove - we have another keyframe
                recyclePacket(buffer.removeFirst());
            } else if (!first.isKeyFrame) {
                // Not a keyframe, safe to remove
                recyclePacket(buffer.removeFirst());
            } else {
                // This is the only keyframe, keep it even if over budget
                break;
            }
            
            // Recalculate duration
            if (buffer.size() > 1) {
                currentDurationUs = buffer.getLast().info.presentationTimeUs - 
                                  buffer.getFirst().info.presentationTimeUs;
            } else {
                currentDurationUs = 0;
                break;
            }
        }
    }
    
    /**
     * Returns all packets for flushing to file.
     * 
     * Ensures the returned list starts with a keyframe for valid MP4 generation.
     * NOTE: Packets are NOT removed from buffer - they will be recycled naturally
     * when they fall out of the time window.
     * 
     * @return List of packets starting with keyframe
     */
    public synchronized List<Packet> getPacketsForFlush() {
        // CRITICAL: deep-copy each packet's bytes into a fresh standalone
        // Packet that does NOT share storage with the live circular buffer.
        //
        // The circular buffer's Packets are pooled — their ByteBuffer storage
        // is reused when packets get pruned via recyclePacket. While the
        // caller holds a reference to a Packet returned from this method,
        // a concurrent add() that exhausts the pool will recycle the SAME
        // packet's ByteBuffer and overwrite its bytes with a new frame —
        // producing an MP4 where the flush packet's PTS metadata points at
        // a different frame's bitstream. The decoder hits a corrupted NAL
        // at the pre-record/live boundary (~5 sec into playback) and the
        // file appears to die at that timestamp.
        //
        // Snapshot here decouples the returned packets from the recycling
        // pool. Cost: ~50-180 KB × packet_count of fresh allocation per
        // flush trigger (≤ a few MB total), one-time, on the trigger thread.
        List<Packet> result = new ArrayList<>();
        boolean foundKeyFrame = false;
        int totalSize = 0;

        for (Packet p : buffer) {
            if (p.isKeyFrame) {
                foundKeyFrame = true;
            }
            if (!foundKeyFrame) continue;

            // Snapshot: fresh Packet whose ByteBuffer is private to this
            // returned list. Independent of the pool / circular buffer.
            Packet snap = new Packet(p.info.size);
            // Copy bytes. p.data is a flipped direct ByteBuffer (limit=size,
            // position=0 after flip in copyFrom). Duplicate so we don't
            // disturb the live packet's position cursor for any concurrent
            // observer.
            ByteBuffer src = p.data.duplicate();
            src.position(0);
            src.limit(p.info.size);
            snap.data.clear();
            snap.data.put(src);
            snap.data.flip();
            // Mirror metadata — same PTS, flags, size; isKeyFrame too.
            snap.info.set(0, p.info.size, p.info.presentationTimeUs, p.info.flags);
            snap.isKeyFrame = p.isKeyFrame;
            result.add(snap);
            totalSize += p.info.size;
        }

        logger.info(String.format("Flushing %d packets (%.1f sec, %d keyframes, %.1f MB snapshot)",
                result.size(),
                result.isEmpty() ? 0 : (result.get(result.size()-1).info.presentationTimeUs -
                                       result.get(0).info.presentationTimeUs) / 1_000_000.0,
                (int) result.stream().filter(p -> p.isKeyFrame).count(),
                totalSize / 1024.0 / 1024.0));

        return result;
    }
    
    /**
     * Clears the buffer and recycles all packets back to pool.
     */
    public synchronized void clear() {
        // Recycle EVERYTHING back to pool
        while (!buffer.isEmpty()) {
            recyclePacket(buffer.poll());
        }
        currentDurationUs = 0;
        keyframeCount = 0;
        logger.info("Buffer cleared (packets recycled to pool)");
    }
    
    /**
     * Gets current buffer statistics.
     * 
     * @return Human-readable stats string
     */
    public synchronized String getStats() {
        return String.format("Buffer: %d packets, %.1f sec, %d keyframes, pool=%d free", 
                buffer.size(), 
                currentDurationUs / 1_000_000.0,
                keyframeCount,
                pool.size());
    }
    
    /**
     * Gets the number of packets in buffer.
     * 
     * @return Packet count
     */
    public synchronized int size() {
        return buffer.size();
    }
    
    /**
     * Gets the current buffer duration in seconds.
     * 
     * @return Duration in seconds
     */
    public synchronized double getDurationSeconds() {
        return currentDurationUs / 1_000_000.0;
    }
    
    /**
     * Gets the maximum buffer duration in microseconds.
     * Used to check if buffer needs to be recreated on settings change.
     * 
     * @return Max duration in microseconds
     */
    public long getMaxDurationUs() {
        return maxDurationUs;
    }

    /**
     * Returns the FPS this pool was sized for (the value used to compute
     * pool capacity at construction). Used by the encoder reuse path to
     * detect FPS mismatches that would otherwise silently shrink the
     * pre-record window.
     */
    public int getSizedForFps() {
        return sizedForFps;
    }

    /**
     * Returns the bitrate (bps) this pool was sized for. Used by the
     * encoder reuse path to detect quality changes (e.g. STANDARD →
     * MAX) that would otherwise leave I-frames being silently dropped
     * because the per-packet ceiling is too small for the new bitrate.
     */
    public int getSizedForBitrate() {
        return sizedForBitrate;
    }
    
    /**
     * Gets the number of free packets in the pool.
     * 
     * @return Free packet count
     */
    public int getPoolFreeCount() {
        return pool.size();
    }
}

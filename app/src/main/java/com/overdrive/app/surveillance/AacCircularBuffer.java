package com.overdrive.app.surveillance;

import com.overdrive.app.logging.DaemonLogger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * AacCircularBuffer — small in-memory ring of recent AAC access units, used to
 * give event recordings audio at frame 0 instead of the previous "5 seconds of
 * silent video at every event start".
 *
 * <p>This is the audio counterpart to {@link H264ByteRingBuffer}, but the
 * design is deliberately simpler: AAC frames at 64 kbps / 48 kHz / mono run
 * ~256 bytes each at 20 ms cadence (~50 frames per second). Even the internal
 * 62-second window is only ~3100 packets and under 1 MB of payload — small
 * enough that a lock-free deque + per-packet heap copies are cheaper and
 * easier to reason about than the seqlock-validated direct-byte arena the
 * video ring needs.
 *
 * <h3>Concurrency model</h3>
 * Single producer (the daemon's audio ingest thread, calling
 * {@link #add(byte[], int, int, long)}) plus a single consumer (the
 * trigger/event thread, calling {@link #drainAll()} or
 * {@link #snapshotRange(long, long)}). {@link #clear()} is called from start-up
 * and audio-disable paths. All operations are wait-free apart from
 * {@link ConcurrentLinkedDeque}'s internal CAS retries.
 *
 * <h3>Eviction</h3>
 * FIFO under a byte budget. The budget is sized for the configured pre-record
 * window with a 1.5× headroom so AAC frame-size variability (CBR is "constant"
 * only on average) doesn't truncate the window when a few back-to-back frames
 * land slightly above the bitrate-time product. At 62 s × 64 kbps that's
 * 62 × 64000 / 8 × 1.5 = 744 000 bytes — still negligible vs. the video
 * ring.
 *
 * <p>Note: the byte counter is best-effort under contention. {@link #drainAll}
 * resets it to zero after polling the deque so any concurrent {@link #add}
 * that lands during the drain (rare; the audio ingest thread is the sole
 * producer and a drain is microseconds) self-corrects on the next add — the
 * absolute count drifts within a packet's worth, which is well inside the
 * 1.5× budget headroom.
 *
 * <h3>What this ring does NOT do</h3>
 * <ul>
 *   <li>It does not synchronize with the muxer's audio track index. Drained
 *       packets that arrive before the muxer's audio track is wired up are
 *       discarded by the caller.</li>
 *   <li>It does not rebase PTSs. The trigger flow forwards each packet's
 *       absolute PTS into {@link HardwareEventRecorderGpu}'s muxer queue and
 *       the existing {@code writeRebasedAudio} machinery anchors them to the
 *       segment's {@code ptsOriginUs}.</li>
 *   <li>It is not bounded by header count. The byte budget alone is enough —
 *       the maximum window's budget at 256 B/packet caps the deque at roughly
 *       3000 entries.</li>
 * </ul>
 */
public class AacCircularBuffer {
    private static final DaemonLogger logger = DaemonLogger.getInstance("AacRing");

    /** Multiplier applied on top of the bitrate-time product to absorb AAC
     *  frame-size variability. Empirically AAC-LC frames at 64 kbps cluster
     *  around 256 B but spike to 350 B+ on transients; 1.5× covers worst-case
     *  bursts without padding the steady-state footprint. */
    private static final double BITRATE_OVERHEAD = 1.5;

    /** One AAC access unit + its presentation timestamp. Immutable for the
     *  lifetime of the ring entry — the producer copies bytes on
     *  {@link #add(byte[], int, int, long)} so the caller may reuse its
     *  source array immediately. */
    public static final class Packet {
        public final byte[] data;
        public final long ptsUs;
        Packet(byte[] data, long ptsUs) {
            this.data = data;
            this.ptsUs = ptsUs;
        }
    }

    private final ConcurrentLinkedDeque<Packet> ring = new ConcurrentLinkedDeque<>();
    private final AtomicInteger byteCount = new AtomicInteger(0);
    /** Total bytes the ring is allowed to hold before FIFO eviction kicks in.
     *  Final after construction — no resize API. */
    private final int byteBudget;

    /**
     * @param maxDurationSeconds Pre-record window the ring is sized for, in
     *                           seconds. Clamped to [1, 62] to mirror
     *                           {@link H264ByteRingBuffer}'s bounds.
     * @param maxBitrateBps      AAC bitrate in bits per second (e.g. 64000
     *                           for the daemon's default 64 kbps capture).
     */
    public AacCircularBuffer(int maxDurationSeconds, int maxBitrateBps) {
        int clampedSec = Math.max(1, Math.min(62, maxDurationSeconds));
        int safeBitrate = Math.max(8000, maxBitrateBps);
        // Floor at 16 KB so a misconfigured bitrate (e.g. 0 from a stale
        // config) never produces a budget so small that every add() instantly
        // evicts itself. 16 KB still holds ~60 packets at 256 B average.
        long budget = (long) ((clampedSec * (long) safeBitrate / 8L) * BITRATE_OVERHEAD);
        this.byteBudget = (int) Math.max(16 * 1024L, budget);
        logger.info("AacCircularBuffer ready: budget=" + byteBudget
            + " B (duration=" + clampedSec + "s, bitrate="
            + (safeBitrate / 1000) + " kbps)");
    }

    /**
     * Append one AAC access unit to the ring. Bytes are copied — the caller's
     * source array may be reused immediately.
     *
     * <p>Evicts oldest packets in FIFO order until the byte budget is
     * satisfied. A pathologically large single packet (greater than the
     * entire budget) is dropped silently; that scenario is impossible with
     * AAC-LC at 64 kbps but the guard keeps a corrupt ingest stream from
     * wiping the ring.
     *
     * @param data    Source byte array
     * @param offset  Start offset within {@code data}
     * @param length  Valid byte count
     * @param ptsUs   Presentation timestamp in microseconds, on the same
     *                wall-clock origin as the video encoder's PTSs
     */
    public void add(byte[] data, int offset, int length, long ptsUs) {
        if (data == null || length <= 0) return;
        if (offset < 0 || offset + length > data.length) return;
        if (length > byteBudget) {
            // Defensive: a single packet bigger than the entire budget would
            // loop forever in eviction (it'd evict everything and still be
            // over budget). Drop it. Should never happen in practice.
            return;
        }
        byte[] copy = new byte[length];
        System.arraycopy(data, offset, copy, 0, length);
        ring.offer(new Packet(copy, ptsUs));
        int total = byteCount.addAndGet(length);
        // Evict oldest until we're back under budget.
        while (total > byteBudget) {
            Packet head = ring.pollFirst();
            if (head == null) break;
            total = byteCount.addAndGet(-head.data.length);
        }
    }

    /**
     * Pull every packet currently in the ring, in PTS / insertion order, and
     * empty the ring. Intended for the event-trigger flow: at trigger time
     * the surveillance pipeline drains everything in the ring into the
     * muxer's write queue alongside the video pre-record flush.
     *
     * <p>Returned list is owned by the caller. Subsequent {@link #add}s
     * populate a fresh window for the next event.
     *
     * <p>Concurrency: byteCount is decremented by exactly the bytes drained,
     * never reset to 0. The previous {@code set(0)} approach raced with
     * {@link #add}: if a producer landed between {@code ring.offer()} and
     * {@code byteCount.addAndGet(length)}, drainAll could pollFirst the new
     * packet and then clobber byteCount to 0, after which the still-pending
     * addAndGet would push byteCount negative (and stay negative until enough
     * subsequent adds repaid the debt). Subtracting only what we actually
     * drained preserves the invariant that byteCount accounts only for
     * packets currently held by the ring.
     */
    public List<Packet> drainAll() {
        List<Packet> out = new ArrayList<>(ring.size());
        long drainedBytes = 0;
        Packet p;
        while ((p = ring.pollFirst()) != null) {
            out.add(p);
            drainedBytes += p.data.length;
        }
        // drainedBytes is bounded by byteBudget (under 1 MB at the configured
        // maximum and default bitrate), so the cast to int is safe —
        // Math.toIntExact() would also work but adds an exception path for an
        // arithmetically impossible overflow.
        if (drainedBytes > 0) {
            byteCount.addAndGet(-(int) drainedBytes);
        }
        return out;
    }

    /**
     * Return references to packets in the inclusive PTS range without
     * removing them from the ring or copying their payload arrays. The
     * returned list is independent, while each immutable {@link Packet}
     * remains shared with the ring.
     *
     * <p>The deque's weakly-consistent iterator preserves insertion order and
     * tolerates concurrent add/eviction. A packet present for the entire
     * traversal is included exactly once when its PTS is in range; packets
     * concurrently entering or leaving the ring may or may not be observed.
     *
     * @return packets in insertion order, or an empty list for an invalid or
     *         empty range
     */
    public List<Packet> snapshotRange(long startPtsUs, long endPtsUs) {
        List<Packet> out = new ArrayList<>();
        if (startPtsUs > endPtsUs) {
            return out;
        }
        for (Packet packet : ring) {
            if (packet.ptsUs >= startPtsUs && packet.ptsUs <= endPtsUs) {
                out.add(packet);
            }
        }
        return out;
    }

    /** Empty the ring without returning anything. O(n) but rarely called. */
    public void clear() {
        ring.clear();
        byteCount.set(0);
    }

    /** @return approximate bytes currently held (may briefly drift under
     *          concurrent add/drain; self-corrects within one add). */
    public int getByteCount() {
        return byteCount.get();
    }

    /** @return current packet count (O(n) on ConcurrentLinkedDeque, but the
     *          ring is small — ~3100 entries at 62 s × 64 kbps). Diagnostic
     *          only. */
    public int getPacketCount() {
        return ring.size();
    }

    /** @return configured byte budget (final after construction). */
    public int getByteBudget() {
        return byteBudget;
    }
}

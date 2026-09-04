package com.overdrive.app.roadsense.detect

/**
 * Daemon-side [ImuStream]: the receiver end of the app→daemon IMU pipe (D-023).
 *
 * Unlike an app-side sensor stream, this one has no SensorManager — samples
 * arrive already decoded (the controller hands it the lists pulled off an
 * `IMU_BATCH` IPC frame via [ImuFrameCodec]). So [start]/[stop] here are
 * bookkeeping only (track whether we're meant to be live, for stall
 * detection); the actual data motion is [feed].
 *
 * Why implement [ImuStream] at all if it's just fed? Because the pipeline
 * (GravityFrame → EventDetector …) consumes an `ImuStream.Listener` and must not
 * know which side produced the samples. Same interface app-side, daemon-side, and
 * in replay tests — that's the whole point of the seam (D-023).
 *
 * Threading: [feed] is called on the IPC reader thread (one batch at a time, the
 * IPC server is one-line-per-connection). It forwards synchronously to the
 * listener, so the pipeline runs on that thread. That's intentional and matches
 * the single-threaded contract of GravityFrame/EventDetector — there is exactly
 * one feeder. Do not call [feed] from multiple threads concurrently.
 */
class DaemonImuStream : ImuStream {

    @Volatile private var listener: ImuStream.Listener? = null
    @Volatile private var live = false
    @Volatile private var lastFeedMs = 0L

    override fun setListener(listener: ImuStream.Listener?) {
        this.listener = listener
    }

    override fun start(rate: ImuStream.Rate) {
        live = true
        listener?.onStreamState(ImuStream.State.ACTIVE)
    }

    override fun stop() {
        live = false
        listener?.onStreamState(ImuStream.State.UNAVAILABLE)
    }

    /**
     * Feed one decoded batch into the pipeline. Accel and gyro are delivered in
     * timestamp order so the consumer sees a coherent interleaved stream. Returns
     * the number of samples forwarded (diagnostics).
     *
     * @param nowMs wall-clock for stall bookkeeping (injected for testability).
     */
    fun feed(decoded: ImuFrameCodec.Decoded, nowMs: Long): Int {
        val l = listener ?: return 0
        lastFeedMs = nowMs
        if (!live) {
            // First data implies the stream is active even if start() lagged.
            live = true
            l.onStreamState(ImuStream.State.ACTIVE)
        }

        // Merge accel + gyro by timestamp so downstream sees them in real order.
        // Both lists are already individually time-ordered (sidecar appends in
        // sensor-callback order), so this is a linear two-pointer merge — no sort,
        // no allocation beyond the loop. Ties: deliver accel first (the detection
        // channel; gyro only gates rejection, order-insensitive at ms scale).
        val a = decoded.accel
        val g = decoded.gyro
        var i = 0
        var j = 0
        while (i < a.size && j < g.size) {
            if (a[i].tMs <= g[j].tMs) {
                l.onAccel(a[i]); i++
            } else {
                l.onGyro(g[j]); j++
            }
        }
        while (i < a.size) { l.onAccel(a[i]); i++ }
        while (j < g.size) { l.onGyro(g[j]); j++ }
        return a.size + g.size
    }

    /**
     * True if we've been [live] but no batch has arrived within [stallMs] — the
     * sidecar likely died or the car stopped. The controller uses this to surface
     * a STALLED state and (optionally) confirm the sidecar should be running.
     */
    fun isStalled(nowMs: Long, stallMs: Long = DEFAULT_STALL_MS): Boolean =
        live && lastFeedMs > 0L && (nowMs - lastFeedMs) > stallMs

    companion object {
        /** No IMU batch for this long while live ⇒ stalled. ~1 s = ~10 missed
         *  100 ms batches; well beyond normal jitter. */
        const val DEFAULT_STALL_MS = 1_000L
    }
}

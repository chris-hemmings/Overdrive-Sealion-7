package com.overdrive.app.roadsense.source

import com.overdrive.app.byd.BydDataCollector
import com.overdrive.app.roadsense.detect.VehicleDynamics

/**
 * Adapts the daemon's already-running [BydDataCollector] singleton into the
 * RoadSense [VehicleDynamics] contract (D-020).
 *
 * RoadSense does NOT start its own BYD polling — that would double the SDK load
 * and split-brain the telemetry (user mandate: reuse existing instances). The
 * collector lives in the CameraDaemon process and is started/owned by it, so
 * RoadSense (also daemon-side, D-023) just reads the latest immutable snapshot
 * via `getData()` on each detector tick. `getData()` returns an `AtomicReference`
 * snapshot — lock-free, safe from any thread.
 *
 * ## Staleness note (carried into VehicleDynamics.brakeAgeMs)
 * The collector polls vehicle data on a fixed cadence (~5 s ACC-on, ~90 s parked
 * per F-011 / the collector's constants). Speed + steering are relatively fresh;
 * brake/accel/gear can be up to a poll-interval old. We surface that age in
 * `brakeAgeMs` (now − snapshot.timestamp) so `RejectionFilter` can DISCOUNT stale
 * pedal/gear readings near a fast ~200 ms event rather than trusting a 4 s-old
 * "brake 90%". This is exactly why the contract carries the age.
 *
 * FAST DYNAMICS (R-PERF-4, implemented): the collector now exposes an opt-in,
 * narrowly-scoped fast poll of ONLY brake/accel/gear/speed (BydDataCollector
 * .getFastDynamics(), ~250 ms), which RoadSenseController starts while the feature
 * is enabled + DRIVING. When that tuple is present AND fresher than the main
 * snapshot, we use it — so pedal/gear are event-aligned to ~200 ms jolts instead of
 * up to ~5 s stale. When it's absent (RoadSense off / not driving / unsupported
 * trim) we transparently fall back to the 5 s `getData()` snapshot, and `brakeAgeMs`
 * still reports the true age so RejectionFilter discounts stale pedal/gear exactly
 * as before. This is the "faster cadence using the daemon's own device handles, not
 * a competing poller" path the original note prescribed.
 *
 * Pure read-through; no state, thread-safe. `nowMs` is injected so this stays
 * unit-testable (no System.currentTimeMillis baked in).
 */
class VehicleDataSource(
    private val collector: () -> BydDataCollector = { BydDataCollector.getInstance() },
) {

    /** The underlying collector if initialized, else null — for the controller to
     *  start/stop the scoped fast-dynamics poll (R-PERF-4). Returns null pre-init so
     *  the controller's start/stop calls are safe no-ops during daemon boot. */
    fun collectorOrNull(): BydDataCollector? = collector().takeIf { it.isInitialized }

    /**
     * Latest vehicle dynamics, or null if the collector isn't initialized yet
     * (daemon still booting). Caller treats null as "no rejection context this
     * tick" — it should NOT fabricate a default snapshot, since a fake
     * "gear=0/brake=0" could wrongly pass or fail rejection.
     *
     * @param nowMs current wall-clock ms (injected for testability).
     */
    fun latest(nowMs: Long): VehicleDynamics? {
        val c = collector()
        if (!c.isInitialized) return null
        val d = c.getData() ?: return null

        // Prefer the fast-dynamics tuple for the pedal/gear/speed fields when it's
        // running AND fresher than the main snapshot (R-PERF-4). Steering is NOT in
        // the fast tuple — it's a low-latency push listener already, so we always
        // read it from the main snapshot. brakeAgeMs reflects whichever source we
        // used for the pedal/gear group, so RejectionFilter's staleness gate stays
        // honest in both modes.
        val fast = c.fastDynamics
        if (fast != null && fast.timestamp >= d.timestamp) {
            val ageMs = (nowMs - fast.timestamp).coerceAtLeast(0L)
            return VehicleDynamics(
                tMs = fast.timestamp,
                speedKmh = fast.speedKmh.toFloat(),
                brakePercent = fast.brakePercent,
                accelPercent = fast.accelPercent,
                steeringAngleDeg = d.steeringAngleDegrees.toFloat(),
                gearMode = fast.gearMode,
                brakeAgeMs = ageMs,
            )
        }

        val ageMs = (nowMs - d.timestamp).coerceAtLeast(0L)
        return VehicleDynamics(
            tMs = d.timestamp,
            speedKmh = d.speedKmh.toFloat(),
            brakePercent = d.brakePercent,
            accelPercent = d.accelPercent,
            steeringAngleDeg = d.steeringAngleDegrees.toFloat(),
            gearMode = d.gearMode,
            brakeAgeMs = ageMs,
        )
    }
}

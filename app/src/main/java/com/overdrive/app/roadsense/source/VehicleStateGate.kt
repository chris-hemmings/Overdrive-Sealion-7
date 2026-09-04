package com.overdrive.app.roadsense.source

/**
 * Decides RoadSense's operating regime from vehicle state (D-021).
 *
 * RoadSense only has value while the car is actually being driven, so it scales
 * its own resource use by gear + ACC into three regimes:
 *
 *   DRIVING  (gear D/M/S, ACC on) — full rate: IMU @ ~100 Hz, full pipeline.
 *   RELAXED  (gear P/N/R, ACC on) — you cannot hit a pothole while stopped/parked;
 *            drop IMU to a slow rate (or unregister), pause event detection, keep
 *            light state resident for instant resume when D returns.
 *   OFF      (ACC off)            — completely silent: unregister IMU, stop the
 *            detector, release buffers/threads, persist pending state. ~zero cost.
 *
 * This is PURE policy — no Android, no sensors, no side effects. The owning
 * controller calls [evaluate] whenever gear/ACC change and acts on the returned
 * [Regime] (and on [transition] to know what to actually start/stop). Keeping it
 * pure means the regime logic is unit-testable in isolation, which matters
 * because mis-gating either wastes battery (too hot) or misses hazards (too cold).
 *
 * ACC takes priority over gear: ACC off ⇒ OFF regardless of last-known gear
 * (the bus may report a stale gear after shutdown; ACC is the authoritative
 * "is the car alive" signal — AccMonitor, see D-020).
 */
object VehicleStateGate {

    enum class Regime {
        /** Driving: full detection. */
        DRIVING,
        /** ACC on but not in a forward gear: paused, lightweight, resume-ready. */
        RELAXED,
        /** ACC off: silent, resources released. */
        OFF,
    }

    /** Gear constants from BYDAutoGearboxDevice (P=1 R=2 N=3 D=4 M=5 S=6). */
    private const val GEAR_D = 4
    private const val GEAR_M = 5
    private const val GEAR_S = 6

    /**
     * @param accOn               AccMonitor.isAccOn()
     * @param accAuthoritative    AccMonitor.isAccStateAuthoritative() — false means
     *                            we haven't yet received a real ACC reading. When
     *                            unauthoritative we must NOT go OFF on a default
     *                            false (that would silence a genuinely-driving car
     *                            before the first ACC IPC). We treat unauthoritative
     *                            as "assume the car may be alive" → at least RELAXED.
     * @param gearMode            BydVehicleData.gearMode (1..6); any non-forward
     *                            value (incl. 0/unknown) is treated as non-driving.
     */
    fun evaluate(accOn: Boolean, accAuthoritative: Boolean, gearMode: Int): Regime {
        // ACC is the authoritative "car alive" gate — but only once we actually
        // have a real reading. Before that, never hard-OFF (would miss the start
        // of a drive); fall through to gear-based decision instead.
        if (accAuthoritative && !accOn) return Regime.OFF

        return if (isForwardGear(gearMode)) Regime.DRIVING else Regime.RELAXED
    }

    private fun isForwardGear(gearMode: Int): Boolean =
        gearMode == GEAR_D || gearMode == GEAR_M || gearMode == GEAR_S

    /**
     * Describes what the controller should do when moving [from] → [to], so the
     * controller doesn't re-derive start/stop intent. Returned by [transition].
     */
    data class Action(
        val startImu: Boolean,    // (re)register the IMU listener at full rate
        val stopImu: Boolean,     // unregister the IMU listener entirely
        val slowImu: Boolean,     // keep IMU but at a relaxed rate
        val persistState: Boolean,// flush pending hazards/calibration to store
    )

    /**
     * Map a regime change to concrete controller actions. Idempotent for
     * from==to (returns the steady-state action for [to]).
     */
    fun transition(from: Regime, to: Regime): Action = when (to) {
        Regime.DRIVING -> Action(
            startImu = true, stopImu = false, slowImu = false,
            persistState = false,
        )
        Regime.RELAXED -> Action(
            // Keep the IMU but slow it; pause the detector. Don't tear everything
            // down — a light → D flip should resume instantly at the next light.
            startImu = false, stopImu = false, slowImu = true,
            // Persist when we ARRIVE in RELAXED from DRIVING (just finished moving)
            // so an unexpected later kill doesn't lose the drive's hazards.
            persistState = (from == Regime.DRIVING),
        )
        Regime.OFF -> Action(
            // Car off: release everything, persist, go silent (~zero cost).
            startImu = false, stopImu = true, slowImu = false,
            persistState = true,
        )
    }
}

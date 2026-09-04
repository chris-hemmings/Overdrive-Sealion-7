package com.overdrive.app.roadsense.source

import com.overdrive.app.monitor.GpsMonitor
import com.overdrive.app.roadsense.detect.ALTITUDE_UNKNOWN
import com.overdrive.app.roadsense.detect.Pose

/**
 * Adapts the daemon's already-running [GpsMonitor] singleton into the RoadSense
 * [Pose] contract (D-020).
 *
 * RoadSense does NOT register its own `LocationManager` — `LocationSidecarService`
 * (app side) already streams GPS to the daemon, and `GpsMonitor` (daemon side)
 * holds the latest fix with staleness metadata. RoadSense, running in the daemon
 * (D-023), reads that singleton. The `GpsRingBuffer` (separate stage) keeps a
 * short history of these [Pose]s for back-projection with fix-latency compensation
 * (R-EXT-2) — this adapter just exposes the current fix.
 *
 * GpsMonitor accessors are simple volatile reads (lat/lng/speed/heading/accuracy
 * + lastUpdate), safe from any thread.
 *
 * Pure read-through, no state, thread-safe. `nowMs` injected for testability.
 */
class LocationSource(
    private val monitor: () -> GpsMonitor = { GpsMonitor.getInstance() },
) {

    /**
     * Latest pose, or null if we have no usable fix yet. A 0/0 location or an
     * absurdly stale fix returns null rather than a bogus Pose, so the pipeline
     * never localizes a hazard at null-island or kilometres from reality.
     *
     * @param nowMs       current wall-clock ms (injected for testability).
     * @param maxAgeMs    fixes older than this are treated as no-fix. Default
     *                    aligns with "a few seconds" — older than that and a
     *                    100 Hz event can't be back-projected accurately anyway.
     */
    fun latest(nowMs: Long, maxAgeMs: Long = DEFAULT_MAX_FIX_AGE_MS): Pose? {
        val m = monitor()
        val lat = m.latitude
        val lng = m.longitude
        // Reject the "no fix" sentinel (0,0) — RoadSense must never map a hazard
        // at null-island. A real fix in the Gulf of Guinea is not our problem.
        if (lat == 0.0 && lng == 0.0) return null
        val age = (nowMs - m.lastUpdate).coerceAtLeast(0L)
        if (m.lastUpdate <= 0L || age > maxAgeMs) return null
        return Pose(
            tMs = m.lastUpdate,
            lat = lat,
            lng = lng,
            speedMps = m.speed,         // GpsMonitor.getSpeed() is m/s (Location.getSpeed)
            bearingDeg = m.heading,
            accuracyM = m.accuracy,
            // GpsMonitor stores 0.0 when the fix had no altitude (Location.hasAltitude
            // false) — indistinguishable from a real sea-level fix, so map 0.0 to
            // UNKNOWN and stay fail-open rather than risk a bogus level mismatch.
            altitudeM = m.altitude.let { if (it == 0.0) ALTITUDE_UNKNOWN else it },
        )
    }

    /**
     * Display-only forward extrapolation of a fix to `nowMs`, for the warn/distance
     * path ONLY (NOT detection localization — that path keeps the raw fix and does
     * its own latency-compensated back-projection via [com.overdrive.app.roadsense.detect.GpsRingBuffer]).
     *
     * The GPS fix is ~1–2 Hz and step-held flat between pushes, so a hazard
     * "distance ahead" computed from the raw fix freezes for a whole fix interval
     * then jumps — while the map puck (VehicleMotionEstimator) glides. That desync
     * is what reads as "the distance lags my approach". Here we advance the pose
     * along its own bearing by speed·dt so the displayed range counts down smoothly
     * between fixes.
     *
     * STATELESS + thread-safe: pure function of [pose] + [nowMs] (no shared buffer),
     * so it is safe to call from the warn-tick thread without racing the IPC GPS
     * writer. Uses the SAME guards as GpsRingBuffer.deadReckon — only extrapolate
     * when genuinely moving ([MIN_EXTRAPOLATE_SPEED_MPS]) with a decent fix
     * ([MAX_EXTRAPOLATE_ACCURACY_M]), and cap the horizon ([MAX_EXTRAPOLATE_MS]) so
     * a long gap can't fling the pose far off-track. Outside the guard band the raw
     * pose is returned unchanged (fail-safe to today's behaviour).
     */
    fun forwardExtrapolated(pose: Pose, nowMs: Long): Pose {
        val dtMs = (nowMs - pose.tMs).coerceAtLeast(0L)
        if (dtMs <= 0L || dtMs > MAX_EXTRAPOLATE_MS ||
            pose.speedMps < MIN_EXTRAPOLATE_SPEED_MPS ||
            pose.accuracyM > MAX_EXTRAPOLATE_ACCURACY_M
        ) {
            return pose
        }
        val distM = pose.speedMps * (dtMs / 1000.0)
        val brgRad = Math.toRadians(pose.bearingDeg.toDouble())
        val dLat = (distM * kotlin.math.cos(brgRad)) / EARTH_M_PER_DEG_LAT
        val dLng = (distM * kotlin.math.sin(brgRad)) /
            (EARTH_M_PER_DEG_LAT * kotlin.math.cos(Math.toRadians(pose.lat)).coerceAtLeast(0.01))
        return pose.copy(
            tMs = nowMs,
            lat = pose.lat + dLat,
            lng = pose.lng + dLng,
            // Degrade accuracy by how far we guessed, mirroring deadReckon.
            accuracyM = pose.accuracyM + distM.toFloat(),
        )
    }

    companion object {
        /** Fixes older than ~5 s are not useful for back-projecting a fast event. */
        const val DEFAULT_MAX_FIX_AGE_MS = 5_000L

        /** Metres per degree of latitude (mean). Same constant family as GpsRingBuffer. */
        private const val EARTH_M_PER_DEG_LAT = 111_320.0
        /** Cap the display extrapolation horizon so a stale fix can't fling the puck. */
        private const val MAX_EXTRAPOLATE_MS = 2_000L
        /** Below this the GPS bearing is noise — hold the raw fix (matches HEADING_RELIABLE_MPS). */
        private const val MIN_EXTRAPOLATE_SPEED_MPS = 2.5f
        /** Don't extrapolate a low-quality fix (would amplify its error). */
        private const val MAX_EXTRAPOLATE_ACCURACY_M = 30f
    }
}

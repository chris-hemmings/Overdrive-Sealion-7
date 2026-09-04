package com.overdrive.app.roadsense.detect

/**
 * Short rolling history of vehicle [Pose]s, used to place a detected hazard at
 * the position the car ACTUALLY was when the event happened — not where GPS
 * reported it a beat later (R-EXT-2).
 *
 * ## Why back-projection is necessary
 * Automotive GPS lags true position by ~0.5–1.5 s (receiver + fusion delay). The
 * IMU event timestamp (`DetectionCandidate.tMs`) is, by contrast, essentially
 * real-time. If we localized a bump at "the latest GPS fix", every hazard would
 * land 10–20 m past its true spot at speed — the difference between a usable map
 * and a useless one. So we:
 *   1. keep a few seconds of recent fixes here,
 *   2. when an event fires at `tEventMs`, look up where the car was at
 *      `tEventMs − fixLatencyMs`, interpolating between the two bracketing fixes.
 *
 * ## Fix-latency
 * `fixLatencyMs` is the GPS pipeline delay. It can be a fixed estimate (default
 * ~700 ms) or, better, estimated per-session by cross-correlating GPS speed with
 * the zero-latency BYD wheel speed (`BydVehicleData.speedKmh`) — that calibration
 * is a separate concern; this buffer just consumes whatever latency it's told.
 *
 * Fixed-size primitive-ish ring (array of Pose refs, head/count), no per-add
 * allocation beyond the caller's Pose. Not thread-safe — the daemon feeds GPS
 * and queries on its own single RoadSense thread. Pure (no Android, no clock).
 */
class GpsRingBuffer(
    /** Capacity in fixes. At ~2 Hz GPS, 16 ≈ 8 s of history — ample for a
     *  sub-2 s back-projection plus interpolation headroom. */
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    private val buf = arrayOfNulls<Pose>(capacity)
    private var head = 0   // next write index
    private var count = 0

    /** Add a fix. Caller filters out null-island / stale fixes (LocationSource).
     *  De-dups a repeat of the most-recent timestamp (the GPS layer can re-emit the
     *  same fix at the poll cadence): a duplicate tMs adds no information but would
     *  consume a ring slot and shrink the distinct-fix back-projection horizon, and
     *  a same-tMs bracket makes interpolate()'s span 0 (it already guards span<=0).
     *  We REPLACE the latest in place so the freshest field values still win. */
    fun add(pose: Pose) {
        if (count > 0) {
            val lastIdx = (head - 1 + capacity) % capacity
            if (buf[lastIdx]?.tMs == pose.tMs) { buf[lastIdx] = pose; return }
        }
        buf[head] = pose
        head = (head + 1) % capacity
        if (count < capacity) count++
    }

    /** Most recent fix, or null if empty. */
    fun latest(): Pose? = if (count == 0) null else buf[(head - 1 + capacity) % capacity]

    /**
     * Back-project: where was the car at `tEventMs − fixLatencyMs`?
     *
     * Returns a [Pose] interpolated between the two fixes that bracket the target
     * time. Falls back to the nearest fix if the target lands outside the buffered
     * range (e.g. event right after start, or a GPS gap). Returns null only if the
     * buffer is empty.
     *
     * @param tEventMs     IMU event timestamp (DetectionCandidate.tMs).
     * @param fixLatencyMs estimated GPS pipeline delay to subtract.
     */
    fun backProject(tEventMs: Long, fixLatencyMs: Long): Pose? {
        if (count == 0) return null
        val target = tEventMs - fixLatencyMs

        // Walk the buffer oldest→newest collecting the bracketing pair.
        var older: Pose? = null
        var newer: Pose? = null
        for (i in 0 until count) {
            val idx = (head - count + i + capacity) % capacity
            val p = buf[idx] ?: continue
            if (p.tMs <= target) {
                older = p
            } else {
                newer = p
                break
            }
        }

        return when {
            older != null && newer != null -> interpolate(older, newer, target)
            // Target is NEWER than every buffered fix. NOTE: with the current
            // GPS_POLL_MS≈500 cadence and DEFAULT_FIX_LATENCY_MS≈700, target
            // (event−700) is usually OLDER than the ~500 ms-fresh newest fix, so the
            // interpolation branch above is the normal path; this branch is the
            // RARE-GAP fallback (a dropped/sparse fix run) — not the common case
            // (audit detection #10). When it IS hit, don't return the stale fix as-is
            // (audit detection #4 — that lands the hazard where the car already was):
            // dead-reckon forward from the newest fix, but only when its bearing is
            // trustworthy (see deadReckon's guards).
            older != null -> deadReckon(older, target)
            newer != null -> newer   // target older than everything — use oldest fix
            else -> latest()
        }
    }

    /**
     * Project [from] forward to [target] assuming constant speed + bearing. Caps
     * the extrapolation horizon so a long GPS gap doesn't fling the point miles
     * away; beyond the cap we just return the last fix (best we honestly have).
     */
    private fun deadReckon(from: Pose, target: Long): Pose {
        val dtMs = target - from.tMs
        // Refuse to extrapolate along an untrustworthy bearing (audit detection #10):
        // GPS bearing is noise at low speed (the controller marks heading unreliable
        // below ~2.5 m/s) and a wrong bearing flings the point sideways up to
        // MAX_DEAD_RECKON_MS*speed metres off-track — degrading localization exactly
        // when GPS is already weak. Below the heading-reliable floor (or with a poor
        // fix) we keep the last real fix rather than project along a noisy heading.
        if (dtMs <= 0L || dtMs > MAX_DEAD_RECKON_MS ||
            from.speedMps < HEADING_RELIABLE_MPS || from.accuracyM > MAX_DEAD_RECKON_ACCURACY_M
        ) return from
        val distM = from.speedMps * (dtMs / 1000.0)
        val brgRad = Math.toRadians(from.bearingDeg.toDouble())
        // Equirectangular step — fine over the few metres a sub-second gap covers.
        val dLat = (distM * kotlin.math.cos(brgRad)) / EARTH_M_PER_DEG_LAT
        val dLng = (distM * kotlin.math.sin(brgRad)) /
            (EARTH_M_PER_DEG_LAT * kotlin.math.cos(Math.toRadians(from.lat)).coerceAtLeast(0.01))
        return Pose(
            tMs = target,
            lat = from.lat + dLat,
            lng = from.lng + dLng,
            speedMps = from.speedMps,
            bearingDeg = from.bearingDeg,
            // Extrapolated → degrade accuracy by the distance we guessed.
            accuracyM = from.accuracyM + distM.toFloat(),
            // Altitude doesn't change over a sub-2 s horizontal extrapolation.
            altitudeM = from.altitudeM,
        )
    }

    /** Linear interpolation of position/speed/bearing between two fixes at [target]. */
    private fun interpolate(a: Pose, b: Pose, target: Long): Pose {
        val span = (b.tMs - a.tMs)
        if (span <= 0L) return a
        val f = ((target - a.tMs).toDouble() / span.toDouble()).coerceIn(0.0, 1.0)
        return Pose(
            tMs = target,
            lat = a.lat + (b.lat - a.lat) * f,
            lng = a.lng + (b.lng - a.lng) * f,
            speedMps = (a.speedMps + (b.speedMps - a.speedMps) * f.toFloat()),
            bearingDeg = interpolateBearing(a.bearingDeg, b.bearingDeg, f.toFloat()),
            // Localization confidence is no better than the worse of the two fixes.
            accuracyM = maxOf(a.accuracyM, b.accuracyM),
            // Interpolate altitude only when BOTH fixes have it; else unknown (fail-open).
            altitudeM = if (altitudeKnown(a.altitudeM) && altitudeKnown(b.altitudeM))
                a.altitudeM + (b.altitudeM - a.altitudeM) * f
            else ALTITUDE_UNKNOWN,
        )
    }

    /** Shortest-arc bearing interpolation (handles the 350°→10° wrap). */
    private fun interpolateBearing(a: Float, b: Float, f: Float): Float {
        var diff = (b - a) % 360f
        if (diff > 180f) diff -= 360f
        if (diff < -180f) diff += 360f
        var r = a + diff * f
        r %= 360f
        if (r < 0f) r += 360f
        return r
    }

    fun reset() {
        head = 0; count = 0
        for (i in buf.indices) buf[i] = null
    }

    companion object {
        const val DEFAULT_CAPACITY = 16
        /** Provisional fixed GPS pipeline latency until per-session estimation lands. */
        const val DEFAULT_FIX_LATENCY_MS = 700L
        /** Cap forward dead-reckoning; beyond this a GPS gap is too long to trust an
         *  extrapolation, so we fall back to the last real fix. ~2 s. */
        const val MAX_DEAD_RECKON_MS = 2_000L
        /** Speed floor below which GPS bearing is too noisy to extrapolate along
         *  (matches the controller's HEADING_RELIABLE_MPS). Below it dead-reckon
         *  keeps the last fix rather than project along a garbage heading. */
        const val HEADING_RELIABLE_MPS = 2.5f
        /** Fix-accuracy ceiling for dead-reckoning: a fix this loose already places
         *  the car poorly, so extrapolating from it only compounds the error. */
        const val MAX_DEAD_RECKON_ACCURACY_M = 30f
        /** Metres per degree of latitude (WGS-84 mean). */
        const val EARTH_M_PER_DEG_LAT = 111_320.0
    }
}

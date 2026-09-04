package com.overdrive.app.navmap.nav

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Smooth, continuous vehicle motion from SPARSE, jittery GPS fixes.
 *
 * <p>The daemon's `/api/gps` delivers a new fix only every ~1-2s (and re-sends the
 * SAME fix in between), so rendering straight off it makes the puck/camera TELEPORT
 * every couple seconds. This estimator decouples the two cadences:
 *
 * <ul>
 *   <li>{@link #onTruthPoint} — called when a genuinely NEW fix arrives. It rejects
 *       bad-accuracy / out-of-order fixes, derives a stable bearing (from the
 *       position delta when moving, since raw GPS bearing is noisy), and EMA-blends
 *       the fix into a filtered state with a DISTANCE-ADAPTIVE alpha (snap hard on a
 *       small confident jump, ease gently on a big/uncertain one so GPS noise is
 *       absorbed). Speed and bearing are smoothed on their own factors.</li>
 *   <li>{@link #estimate} — called every RENDER FRAME (~12 fps micro-tick). Between
 *       fixes it dead-reckons the position FORWARD from the last filtered state along
 *       the smoothed bearing by {@code speed × elapsed}, capped at
 *       {@link #MAX_DEAD_RECKON_S}. So the puck glides continuously; it never
 *       outruns reality by more than ~1s of travel, and it FREEZES when stationary
 *       (raw GPS wanders when parked).</li>
 * </ul>
 *
 * <p>Pure Kotlin, framework-free (JVM-testable) — mirrors the rest of {@code nav/}.
 * NOT thread-safe; call from a single thread (the map's main/guidance thread).
 *
 * <p>The constants are tuned for a ~1-2s GPS cadence on a head unit; they are the
 * generic technique (accuracy-gated complementary filter + bounded dead-reckoning),
 * not specific to any one app.
 */
class VehicleMotionEstimator {

    /** One filtered motion sample. lat/lng degrees, speed m/s, bearing 0..360°. */
    data class Motion(
        val lat: Double,
        val lng: Double,
        val speedMps: Double,
        val bearingDeg: Double,
        val timestampMs: Long,
    )

    private var filtered: Motion? = null
    private var prevTruth: Motion? = null
    /** Timestamp of the last truth fix we ACCEPTED — to drop identical re-polls. */
    private var lastAcceptedTs: Long = 0L
    /** Last CAN brake-pedal position (0..100) from a truth fix, or null if the CAN bus
     *  didn't report it. Drives the dead-reckon deceleration model in [estimate]. */
    private var lastBrakePercent: Int? = null

    // ── (C) Gyro yaw-rate complementary heading fusion ───────────────────────────
    // SOTA crisp-turn lever: GPS course-over-ground only updates AFTER the car has
    // moved through a corner, so a heading derived from GPS alone always LAGS the
    // turn and the painted path cuts the corner. A gyro yaw-rate (rad/s about true
    // vertical) integrated between fixes turns the heading IN REAL TIME with the
    // wheel. We fuse complementary-style: integrate the gyro fast (the high-pass
    // term) and gently pull the integrated heading back toward the GPS/route-tangent
    // bearing on each truth fix (the low-pass term that kills gyro drift). The gyro
    // gives RELATIVE rotation only (no absolute-heading hardware on this trim, F-010),
    // so GPS/tangent remains the absolute reference; the gyro just fills the 1 Hz gaps.
    //
    // SAFETY: this is strictly additive. [gyroHealthy] starts false and only latches
    // true once gyro samples are arriving AND the integrated heading agrees with the
    // GPS-derived heading (so a wrong axis/sign or a dead/stub sensor can NEVER make
    // the puck worse — it silently decays back to the GPS-only path). All gyro fields
    // are touched from the gyro sample thread + the render/truth thread, so the live
    // heading + health are @Volatile; integration uses only its own fields.
    /** Live gyro-integrated absolute heading (deg, 0..360), or NaN until seeded from
     *  the first truth bearing. This is the heading [estimate] steers by when the gyro
     *  is healthy. */
    @Volatile private var gyroHeadingDeg: Double = Double.NaN
    /** elapsedRealtime-domain ts (ms) of the last integrated gyro sample, for dt. */
    private var lastGyroTsMs: Long = 0L
    /** elapsedRealtime-domain ts (ms) of the most recent gyro sample of ANY kind, so
     *  the render path can tell whether the gyro has gone silent (→ drop to GPS-only).
     *  This is the MONOTONIC clock — [estimate] checks silence against its `nowMonoMs`
     *  arg (also elapsedRealtime), NOT the daemon wall-clock `nowMs` used for dead-
     *  reckon dt (those are different epochs). */
    @Volatile private var lastGyroSeenMs: Long = 0L
    /** Latched gyro-health: true while gyro samples flow AND the integrated heading
     *  tracks GPS. Decays to false on silence (checked in [estimate]) or on a large,
     *  sustained disagreement with GPS heading (checked in [onTruthPoint]). */
    @Volatile private var gyroHealthy: Boolean = false
    /** Consecutive truth fixes where the integrated gyro heading agreed with the
     *  GPS-derived heading — must reach [GYRO_AGREE_FIXES] before we trust the gyro. */
    private var gyroAgreeStreak: Int = 0
    /** Snapshot of [gyroHeadingDeg] taken at the last accepted truth fix (main thread),
     *  so [estimate] can sweep the CTRV arc over the ACTUAL integrated rotation since the
     *  fix (fix-heading → now-heading) rather than re-deriving it from a turn rate. NaN
     *  until first seeded. Written + read only on the main thread (no race). */
    private var gyroHeadingAtFixDeg: Double = Double.NaN

    // ── (B) Curvature-aware dead-reckon (constant-turn-rate / CTRV model) ─────────
    // Dead-reckoning a STRAIGHT line along one bearing flies off the outside of a bend
    // (corner-cut) and ploughs straight through a curved tunnel/cloverleaf during a GPS
    // dropout. When the gyro is healthy the integrated heading sweeps as the car turns,
    // so [estimate] curves the predicted path along the ARC the heading actually traced
    // between the fix and now — the constant-turn-rate-and-velocity model used in vehicle
    // tracking. Closed-form and route-independent, so it tightens curves whether
    // navigating, off-route, or just idle-browsing. Falls back to the straight line when
    // the gyro isn't trusted or the heading barely changed (the κ→0 limit is exactly the
    // straight projection).

    /** True once a first fix has been accepted (so callers can gate rendering). */
    fun hasFix(): Boolean = filtered != null

    /** Whether the gyro is currently trusted for heading (for diagnostics / callers
     *  that want to widen the dead-reckon horizon only when heading won't go stale). */
    fun isGyroHealthy(): Boolean = gyroHealthy

    /**
     * (C) Ingest one gyro yaw-rate sample. [yawRateRps] is the SIGNED rotation rate
     * about TRUE VERTICAL (rad/s) — i.e. already tilt-corrected via the gravity
     * projection (GravityFrame.alongGravity), NOT a raw device axis. [tsMs] is in the
     * elapsedRealtime MONOTONIC domain.
     *
     * Integrates the heading forward at the IMU rate. Sign convention: a positive
     * yaw-rate about the up vector is a counter-clockwise (left) turn in the ENU/
     * heading sense, so compass heading DECREASES — we subtract. If the device frame's
     * sign turns out inverted on this mount, the health latch ([onTruthPoint] agreement
     * check) simply never trusts it and we stay on GPS-only — so a wrong sign degrades
     * gracefully, it never inverts the puck.
     *
     * Called from the gyro sample thread. It RMW-updates the @Volatile [gyroHeadingDeg]
     * while the main thread (onTruthPoint) also seeds/drift-corrects it — a deliberately
     * accepted BENIGN race: @Volatile gives visibility, and the worst case is a single
     * lost integration or correction step that self-heals on the next ~20 ms sample / 1 s
     * fix (the health latch + per-fix re-anchor bound any error; no inverted-puck risk).
     */
    fun onGyroYaw(yawRateRps: Double, tsMs: Long) {
        lastGyroSeenMs = tsMs
        val h = gyroHeadingDeg
        if (h.isNaN()) {
            // Not seeded yet (no truth bearing to anchor to) — just stamp the clock so
            // the first integration step after seeding has a sane dt.
            lastGyroTsMs = tsMs
            return
        }
        val dtS = ((tsMs - lastGyroTsMs).coerceAtLeast(0L)) / 1000.0
        lastGyroTsMs = tsMs
        // Ignore a stale gap (paused listener / huge dt) so we don't integrate a wild
        // jump; the next truth fix re-anchors the heading anyway.
        if (dtS <= 0.0 || dtS > MAX_GYRO_GAP_S) return
        val dDeg = Math.toDegrees(yawRateRps) * dtS
        gyroHeadingDeg = normalize(h - dDeg)
    }

    /** Reset all state (call on nav start / stop so a new trip starts clean). */
    fun reset() {
        filtered = null
        prevTruth = null
        lastAcceptedTs = 0L
        lastBrakePercent = null
        gyroHeadingDeg = Double.NaN
        lastGyroTsMs = 0L
        lastGyroSeenMs = 0L
        gyroHealthy = false
        gyroAgreeStreak = 0
        gyroHeadingAtFixDeg = Double.NaN
    }

    /**
     * Ingest a raw GPS fix. [accuracyM] and [rawBearingDeg] may be null (then we
     * derive bearing from motion / hold the last). [tsMs] is the fix's wall-clock
     * time; an identical re-poll (same or older ts) is ignored so we advance once
     * per real fix. Returns the new filtered [Motion] (or the unchanged current one).
     */
    fun onTruthPoint(
        lat: Double, lng: Double, speedMps: Double,
        rawBearingDeg: Double?, accuracyM: Double?, tsMs: Long,
        brakePercent: Int? = null,
    ): Motion {
        val acc = accuracyM ?: DEFAULT_ACCURACY_M
        val cur = filtered
        // Remember the latest CAN brake position for the dead-reckon decel model.
        // Held across re-polls (a duplicate fix returns early below without clearing it).
        if (brakePercent != null) lastBrakePercent = brakePercent

        // First fix → seed directly.
        if (cur == null) {
            val seed = Motion(lat, lng, speedMps, normalize(rawBearingDeg ?: 0.0), tsMs)
            filtered = seed; prevTruth = seed; lastAcceptedTs = tsMs
            // Seed the gyro-integrated heading from the first usable bearing so the
            // very first integration step has an absolute anchor (gyro is rate-only).
            if (rawBearingDeg != null && speedMps > GYRO_AGREE_MIN_SPEED_MPS) {
                gyroHeadingDeg = normalize(rawBearingDeg)
                gyroHeadingAtFixDeg = gyroHeadingDeg
            }
            return seed
        }
        // Reject obviously-bad accuracy + out-of-order / duplicate re-polls.
        if (acc > MAX_ACCEPTED_ACCURACY_M) return cur
        if (tsMs <= lastAcceptedTs) return cur
        if (tsMs + MAX_OUT_OF_ORDER_FIX_AGE_MS < cur.timestampMs) return cur

        val prev = prevTruth
        val moved = haversine(cur.lat, cur.lng, lat, lng)

        // --- Bearing source: prefer position-delta when clearly moving (stabler than
        //     raw GPS bearing); else raw when fast; else hold the last filtered. ---
        val bearing: Double = when {
            speedMps > 2.2 && rawBearingDeg != null -> rawBearingDeg
            speedMps > 1.2 && prev != null &&
                haversine(prev.lat, prev.lng, lat, lng) > 3.0 ->
                bearingBetween(prev.lat, prev.lng, lat, lng)
            else -> cur.bearingDeg
        }

        // Stationary on both sides → just refresh timestamp, freeze position.
        if (speedMps < STATIONARY_SPEED_MPS && cur.speedMps < STATIONARY_SPEED_MPS) {
            val held = cur.copy(timestampMs = tsMs)
            filtered = held; prevTruth = Motion(lat, lng, speedMps, bearing, tsMs)
            lastAcceptedTs = tsMs
            return held
        }

        val goodAcc = acc <= GOOD_ACCURACY_M
        val speedDelta = speedMps - cur.speedMps
        // Distance-adaptive position alpha: confident small jump → snap (0.68);
        // far/uncertain jump → ease (0.18) to swallow multipath noise.
        val posAlpha = when {
            goodAcc && abs(speedDelta) >= 2.0 && moved <= 30.0 -> 0.68
            goodAcc && moved <= 8.0 -> 0.62
            !goodAcc || moved > 8.0 -> when {
                moved > 35.0 -> if (goodAcc) 0.18 else 0.10
                moved > 12.0 -> if (goodAcc) 0.38 else 0.22
                else -> if (goodAcc) 0.52 else 0.36
            }
            else -> 0.52
        }
        // Speed alpha: respond faster to braking than to mild changes.
        val speedAlpha = when {
            speedDelta < -2.0 -> 0.72
            speedDelta > 3.0 -> 0.55
            else -> 0.35
        }
        // Bearing alpha: snap on a big heading change (turn), ease otherwise.
        val bearingAlpha = when {
            abs(shortestArc(cur.bearingDeg, bearing)) > 80.0 -> 0.10
            speedMps < 3.0 -> 0.08
            else -> 0.22
        }

        val (nlat, nlng) = interpolate(cur.lat, cur.lng, lat, lng, posAlpha)
        var nspeed = cur.speedMps + (speedMps - cur.speedMps) * speedAlpha
        if (speedMps < STATIONARY_SPEED_MPS && nspeed < STATIONARY_SPEED_MPS) nspeed = 0.0
        val nbearing = normalize(cur.bearingDeg + shortestArc(cur.bearingDeg, bearing) * bearingAlpha)

        // ── (C) Complementary gyro-heading correction + health latch ──────────────
        // Only assess the gyro when we have a TRUSTWORTHY absolute reference: a decent
        // fix, moving fast enough that GPS course-over-ground is real (not crawl noise),
        // and a position-delta-derived bearing. Below that we neither trust nor punish
        // the gyro (it just keeps free-running and we re-anchor below).
        if (gyroHeadingDeg.isNaN()) {
            // Seed once we first have a usable absolute heading.
            if (goodAcc && speedMps > GYRO_AGREE_MIN_SPEED_MPS) gyroHeadingDeg = bearing
        } else {
            if (goodAcc && speedMps > GYRO_AGREE_MIN_SPEED_MPS) {
                val disagreeDeg = abs(shortestArc(gyroHeadingDeg, bearing))
                if (disagreeDeg <= GYRO_AGREE_TOLERANCE_DEG) {
                    if (gyroAgreeStreak < GYRO_AGREE_FIXES) gyroAgreeStreak++
                    if (gyroAgreeStreak >= GYRO_AGREE_FIXES) gyroHealthy = true
                } else {
                    // Sustained disagreement → distrust (wrong sign/axis, drift, or a
                    // dead/stub sensor that froze the heading). Fall back to GPS-only.
                    gyroAgreeStreak = 0
                    gyroHealthy = false
                }
            }
            // Pull the integrated heading back toward the absolute GPS bearing (the
            // low-pass term) to bleed off gyro drift, regardless of trust — so when it
            // re-earns trust it's already aligned. Light correction so it doesn't undo
            // the very lead the gyro provides through the turn.
            gyroHeadingDeg = normalize(
                gyroHeadingDeg + shortestArc(gyroHeadingDeg, bearing) * GYRO_GPS_CORRECT_ALPHA
            )
        }
        // Snapshot the (post-correction) integrated heading AT THIS FIX, so estimate()'s
        // CTRV arc sweeps from the fix-time heading to the live now-heading — the actual
        // rotation traced since the fix — instead of double-counting a turn rate.
        gyroHeadingAtFixDeg = gyroHeadingDeg

        val next = Motion(nlat, nlng, nspeed, nbearing, tsMs)
        filtered = next
        prevTruth = Motion(lat, lng, speedMps, bearing, tsMs)
        lastAcceptedTs = tsMs
        return next
    }

    /**
     * Predict the motion at the current instant by dead-reckoning forward from the last
     * filtered fix. Returns null until the first fix. When stationary, returns the held
     * position (no creep). The predicted distance is capped at the dead-reckon horizon
     * ([MAX_DEAD_RECKON_S], extended to [MAX_DEAD_RECKON_GYRO_S] while the gyro is
     * healthy) so a dropped fix can't send the puck flying.
     *
     * TWO CLOCKS, deliberately separate (they are different epochs):
     *  - [nowMs] is the daemon WALL-CLOCK instant (same epoch as the fix timestamp the
     *    estimator stores), used for the dead-reckon dt `nowMs − fix.timestampMs`.
     *  - [nowMonoMs] is the elapsedRealtime MONOTONIC instant (same epoch the gyro
     *    samples are stamped in), used ONLY for the gyro-silence check. Mixing the two
     *    (an earlier bug) made `nowMs − lastGyroSeenMs` ≈ 1.7e12 ≫ silence window, so the
     *    gyro path was permanently inert. Callers pass both.
     *
     * When the gyro is healthy the returned heading is the gyro-integrated heading
     * (real-time turn tracking, no corner-cut) and the path is a constant-turn-rate
     * ARC (B) swept over the heading the gyro ACTUALLY integrated since the fix;
     * otherwise it falls back to the GPS-filtered heading on a straight line.
     */
    fun estimate(nowMs: Long, nowMonoMs: Long): Motion? {
        val m = filtered ?: return null

        // (C/D) Gyro health can decay between fixes if samples stop arriving (listener
        // paused, sensor wedged) — drop to GPS-only so a frozen heading can't strand the
        // puck pointing the wrong way through a long dead-reckon. Silence is measured in
        // the MONOTONIC domain (both operands elapsedRealtime), never the wall clock.
        //
        // KILL-SWITCH ([GYRO_FUSION_ENABLED]): when false, gyroLive is forced false so
        // estimate() takes the v26.8 straight-line GPS-bearing path verbatim (the `else`
        // branches below + the 3 s straight horizon). Disabled by default after the
        // gyro/CTRV arc was found to steer the puck OFF the route line (a mount yaw within
        // the 25° health tolerance latches healthy on straight roads, then bends the
        // predicted point the wrong way on a turn past the 48 m snap-exit). The gyro
        // listener may keep calling onGyroYaw() — it's simply never trusted. Flip to true
        // (and tighten GYRO_AGREE_TOLERANCE_DEG / gate CTRV position to off-route) to
        // re-land the fusion.
        val gyroLive = GYRO_FUSION_ENABLED &&
            gyroHealthy && !gyroHeadingDeg.isNaN() && !gyroHeadingAtFixDeg.isNaN() &&
            (nowMonoMs - lastGyroSeenMs) <= GYRO_SILENCE_MS
        if (gyroHealthy && !gyroLive) gyroHealthy = false   // latch off on silence

        if (m.speedMps < STATIONARY_SPEED_MPS) return m.copy(timestampMs = nowMs)

        // (D) Extend the dead-reckon horizon only when heading won't go stale (gyro live);
        // otherwise keep the conservative straight-line cap so a dropout can't fling it.
        val horizonS = if (gyroLive) MAX_DEAD_RECKON_GYRO_S else MAX_DEAD_RECKON_S
        val dtS = ((nowMs - m.timestampMs).coerceAtLeast(0L) / 1000.0).coerceAtMost(horizonS)
        if (dtS <= 0.0) return m

        // Brake-aware dead-reckoning: at constant speed the puck over-travels when the
        // driver is braking (the next fix lands SHORTER than predicted → the puck
        // snaps back). When the CAN bus reports the brake pedal pressed, shed predicted
        // speed over the window with a constant-decel model so the puck eases toward a
        // stop. The decel rate scales with pedal travel up to BRAKE_MAX_DECEL_MPS2
        // (a firm-but-not-emergency stop); a light/zero brake leaves motion unchanged.
        // dist = integral of v(t) over [0,dtS] with v(t)=v0 - a*t, clamped at the stop.
        val brake = lastBrakePercent ?: 0
        val dist: Double
        if (brake > BRAKE_MIN_PERCENT) {
            val a = BRAKE_MAX_DECEL_MPS2 * (brake.coerceAtMost(100) / 100.0)
            val tStop = m.speedMps / a                       // time to reach 0 speed
            val tEff = minOf(dtS, tStop)                     // don't integrate past the stop
            dist = m.speedMps * tEff - 0.5 * a * tEff * tEff // ∫(v0 - a t) dt
        } else {
            dist = m.speedMps * dtS
        }

        // (B) CTRV arc from the ACTUAL integrated rotation since the fix. When the gyro is
        // live the vehicle traced an arc from the fix-time heading [gyroHeadingAtFixDeg]
        // to the live now-heading [gyroHeadingDeg]; sweeping the dead-reckon distance over
        // that same total heading change makes the puck hug the bend instead of cutting
        // across it — and, because both endpoints are real integrated headings, the arc
        // start/end stay consistent with the painted heading (no rate-vs-integral double
        // count). Work in COMPASS-heading space (0=N, 90=E, clockwise+): position
        // integrates as ∫v·(cosθ,sinθ)dt; with θ swept linearly over the step the
        // closed form is north=k·(sinθ₁−sinθ₀), east=k·(cosθ₀−cosθ₁), k=dist/Δθ. As
        // Δθ→0 this degenerates to the straight projection (the else branch). When the
        // gyro isn't trusted we steer by the GPS-filtered bearing on a straight line —
        // exactly today's behaviour.
        // Snapshot the live @Volatile heading ONCE (the gyro thread can integrate a step
        // mid-frame), so the arc geometry and the painted end-heading derive from the
        // SAME value — the "endHeading == arc endpoint" identity then holds exactly.
        val gyroHeadingNow = gyroHeadingDeg
        val dThetaDeg = if (gyroLive) shortestArc(gyroHeadingAtFixDeg, gyroHeadingNow) else 0.0
        val plat: Double; val plng: Double; val endHeading: Double
        if (gyroLive && abs(dThetaDeg) > MIN_ARC_SWEEP_DEG && dist > 0.5) {
            val th0 = Math.toRadians(gyroHeadingAtFixDeg)
            val dTheta = Math.toRadians(dThetaDeg)           // total compass sweep (rad)
            val th1 = th0 + dTheta
            val k = dist / dTheta                            // = arc radius (signed)
            val north = k * (sin(th1) - sin(th0))
            val east = k * (cos(th0) - cos(th1))
            val dLat = north / 111320.0
            val dLng = east / (111320.0 * cos(Math.toRadians(m.lat)).coerceAtLeast(0.01))
            plat = m.lat + dLat; plng = m.lng + dLng
            // Heading at the END of the arc = the snapshotted live integrated heading
            // (what the puck/camera show mid-turn). Exactly th1 by construction, since
            // th1 = gyroHeadingAtFixDeg + shortestArc(gyroHeadingAtFixDeg, gyroHeadingNow).
            endHeading = gyroHeadingNow
        } else {
            // Straight line: gyro-integrated heading when trusted (real-time turn lead,
            // no corner-cut), else the GPS-filtered bearing (today's behaviour).
            val headDeg = if (gyroLive) gyroHeadingNow else m.bearingDeg
            val p = destinationPoint(m.lat, m.lng, headDeg, dist)
            plat = p.first; plng = p.second
            endHeading = headDeg
        }
        return m.copy(lat = plat, lng = plng, bearingDeg = endHeading, timestampMs = nowMs)
    }

    // ── geo helpers (self-contained; degrees in, meters where noted) ──────────────

    private fun haversine(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2) * sin(dLng / 2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    private fun bearingBetween(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val dLng = Math.toRadians(lng2 - lng1)
        val y = sin(dLng) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dLng)
        return normalize(Math.toDegrees(atan2(y, x)))
    }

    /** Point [distM] meters from (lat,lng) along [bearingDeg]. Returns (lat,lng). */
    private fun destinationPoint(lat: Double, lng: Double, bearingDeg: Double, distM: Double): Pair<Double, Double> {
        val r = 6371000.0
        val d = distM / r
        val br = Math.toRadians(bearingDeg)
        val p1 = Math.toRadians(lat); val l1 = Math.toRadians(lng)
        val p2 = Math.asin(sin(p1) * cos(d) + cos(p1) * sin(d) * cos(br))
        val l2 = l1 + atan2(sin(br) * sin(d) * cos(p1), cos(d) - sin(p1) * sin(p2))
        return Math.toDegrees(p2) to Math.toDegrees(l2)
    }

    /** Linear interpolation between two coords by [t] (0=a, 1=b). Fine at these distances. */
    private fun interpolate(lat1: Double, lng1: Double, lat2: Double, lng2: Double, t: Double): Pair<Double, Double> =
        (lat1 + (lat2 - lat1) * t) to (lng1 + (lng2 - lng1) * t)

    /** Shortest signed angular delta from→to, in -180..180. */
    private fun shortestArc(from: Double, to: Double): Double = ((to - from + 540.0) % 360.0) - 180.0

    private fun normalize(deg: Double): Double = ((deg % 360.0) + 360.0) % 360.0

    companion object {
        /** Master kill-switch for the gyro-yaw + CTRV-arc heading/position fusion.
         *  FALSE = v26.8 behaviour (straight-line dead-reckon along the GPS-filtered
         *  bearing, 3 s horizon). Set FALSE after the fusion was found to push the puck
         *  off the route line via a permissively-latched mount gyro. The integration in
         *  onGyroYaw() + the per-fix health assessment still run (cheap, side-effect-free);
         *  only [estimate]'s use of the result is gated, so flipping this true re-enables
         *  the whole path with no other change. */
        private const val GYRO_FUSION_ENABLED = false

        private const val GOOD_ACCURACY_M = 18.0
        private const val MAX_ACCEPTED_ACCURACY_M = 55.0
        private const val DEFAULT_ACCURACY_M = 20.0
        private const val MAX_OUT_OF_ORDER_FIX_AGE_MS = 1500L
        // Max forward dead-reckon (s). MUST cover the real inter-fix gap or the puck
        // FREEZES at the cap and then JUMPS when the next fix lands — the "laggy /
        // not smooth" symptom. The daemon's /api/gps re-sends the SAME fix for ~2s
        // between genuinely-new fixes, so a 1.2s cap stranded the puck for ~0.8s
        // every cycle. 3.0s comfortably bridges a ~2s cadence (plus a missed poll)
        // while still bounding a fully-dropped fix to ~3s of travel.
        private const val MAX_DEAD_RECKON_S = 3.0
        private const val STATIONARY_SPEED_MPS = 1.4
        // Brake-anticipation dead-reckon (CAN brakePercent). Below BRAKE_MIN_PERCENT the
        // pedal is effectively released (sensor noise / light coast) → constant-speed
        // extrapolation. At full travel the model decelerates at BRAKE_MAX_DECEL_MPS2
        // (~3 m/s² — a firm comfortable stop, well under an ABS emergency ~8 m/s²), so a
        // hard brake eases the puck to a halt over the ~1-2s inter-fix gap instead of
        // sailing past the stop line and snapping back when the next fix lands short.
        private const val BRAKE_MIN_PERCENT = 8
        private const val BRAKE_MAX_DECEL_MPS2 = 3.0

        // ── (C/D) Gyro complementary heading fusion ──────────────────────────────
        /** Below this total heading sweep since the fix (deg) the path is ~straight —
         *  skip the arc math (use the straight projection; the k=dist/Δθ form is ill-
         *  conditioned as Δθ→0 anyway). 1° over a whole inter-fix step is negligible. */
        private const val MIN_ARC_SWEEP_DEG = 1.0
        /** A gyro gap longer than this (s) is a paused/stalled listener, not a real dt —
         *  skip that integration step (the next truth fix re-anchors heading anyway). */
        private const val MAX_GYRO_GAP_S = 0.5
        /** If no gyro sample has arrived within this window (ms), treat the gyro as silent
         *  and fall back to GPS-only heading (a frozen heading must never strand the puck). */
        private const val GYRO_SILENCE_MS = 600L
        /** Min speed (m/s) for the gyro↔GPS heading agreement check — below it GPS course
         *  is crawl-noise, so we neither trust nor punish the gyro on it. ~2.5 m/s ≈ 9 km/h
         *  (matches the project's HEADING_RELIABLE_MPS). */
        private const val GYRO_AGREE_MIN_SPEED_MPS = 2.5
        /** Max gyro↔GPS heading disagreement (deg) still counted as "agreement". Generous
         *  enough to tolerate normal GPS course noise + the gyro's intended turn lead, tight
         *  enough that a wrong sign/axis (≈180°/double) never passes → stays GPS-only. */
        private const val GYRO_AGREE_TOLERANCE_DEG = 25.0
        /** Consecutive agreeing fixes before the gyro is trusted for heading. A few fixes
         *  (~seconds) so a momentary coincidental agreement can't latch a bad sensor on. */
        private const val GYRO_AGREE_FIXES = 3
        /** Per-fix pull of the integrated heading back toward the absolute GPS bearing
         *  (drift bleed-off). Light, so it corrects slow gyro drift without cancelling the
         *  turn lead the gyro provides between fixes. */
        private const val GYRO_GPS_CORRECT_ALPHA = 0.2
        /** Extended dead-reckon horizon (s) while the gyro is healthy: heading no longer
         *  goes stale, so we can ride through a longer GPS dropout (tunnel/underpass) —
         *  curving along the CTRV arc — instead of freezing at the 3 s straight-line cap. */
        private const val MAX_DEAD_RECKON_GYRO_S = 8.0
    }
}

package com.overdrive.app.roadsense.detect

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Turns a raw [DetectionCandidate] into a final [Assessment]: a speed- and
 * vehicle-normalized severity bucket and a 0..1 confidence. (TYPE — breaker vs
 * pothole — is decided by [HazardClassifier], not here; D-040. The [Assessment.type]
 * field is left UNKNOWN and overwritten with the fusion verdict downstream.)
 *
 * Why this exists (R-DET-2, R-DET-4, R-EXT-1, R-EXT-3, D-005, D-011, D-015):
 * the [EventDetector] hands us the *raw* morphology of a vertical event. But the
 * raw jolt is NOT the road severity — it conflates three things:
 *
 *   1. how hard the road actually hit the wheels (what we want),
 *   2. how fast we were going when we hit it (R-DET-4: same pothole at 50 km/h
 *      jolts far harder than at 15 km/h), and
 *   3. how stiff THIS vehicle's suspension is (R-EXT-1: a Seal and a truck feel
 *      the same bump differently).
 *
 * So severity must be a property of the ROAD, not the drive or the car. We:
 *   (1) speed-normalize the peak back to a reference speed (30 km/h),
 *   (2) divide out a per-vehicle scale factor (from the future VehicleCalibrator
 *       — we CONSUME its scalar; we never learn it here, R-EXT-1),
 *   (3) bucket the resulting `normalizedPeak` via the D-011 thresholds.
 *
 * We also compute a confidence (R-EXT-3) that starts LOW when the vehicle
 * calibration is immature (D-015) and is penalized by poor GPS accuracy.
 *
 * HONESTY NOTE: almost every number in here is a first-principles GUESS, not a
 * fit. The thresholds come straight from the provisional D-011 table; the
 * normalization exponent, the noise floor, and all confidence weights are
 * engineering priors chosen to be *reasonable*, not *measured*. Every one is
 * marked PROVISIONAL and is meant to be tuned against the labeled test-drive set
 * (G-1..G-3). The structure is the deliverable; the constants are placeholders.
 *
 * Pure math, no Android deps, stateless — fully unit-testable. `classify` reads
 * only its arguments and the companion consts; it holds no mutable state, so it
 * is trivially thread-safe and reentrant.
 */
class SeverityClassifier {

    /**
     * Classify one candidate.
     *
     * @param candidate          raw event morphology from [EventDetector].
     * @param vehicleScale       per-vehicle suspension factor from the future
     *                           VehicleCalibrator (R-EXT-1). 1.0 = reference
     *                           vehicle. >1 = this car transmits MORE jolt for
     *                           the same road (stiff/short-travel) so we divide
     *                           it out; <1 = soft car, we scale up. We only
     *                           CONSUME this scalar — calibration is learned
     *                           elsewhere.
     * @param calibrationMaturity 0..1 — how well-learned the per-vehicle baseline
     *                           is (D-014 silent auto-learn). 0 = brand-new /
     *                           unknown, 1 = fully settled. Drives the confidence
     *                           floor per D-015 (confidence MUST start low early).
     * @param gpsAccuracyM       reported GPS horizontal accuracy in metres at the
     *                           event. Worse (larger) accuracy ⇒ the hazard will
     *                           be mislocalized, so we shave confidence (R-EXT-3).
     * @return the final [Assessment] (type, severity, confidence, normalizedPeak).
     */
    fun classify(
        candidate: DetectionCandidate,
        vehicleScale: Float,
        calibrationMaturity: Float,
        gpsAccuracyM: Float,
    ): Assessment {
        // ---- 0. Raw amplitude: peak-to-peak span of the biphasic pulse ----
        // We use the peak-to-peak SPAN (peakUp - peakDown), not max(|up|,|down|).
        // WHY: a road hazard is a biphasic event — the suspension is pushed one
        // way then rebounds the other (dip→crest for a pothole, crest→dip for a
        // breaker, see 03-ARCH). The full energy of the encounter lives in the
        // *swing* between the two extremes, not in whichever single extreme
        // happens to be larger. peakUp is the max (≥0-ish), peakDown is the min
        // (≤0-ish), so (peakUp - peakDown) is their separation and is always
        // non-negative. A lone one-sided spike (e.g. a curb tap that only kicks
        // up) still yields a sensible span because the other extreme is ~0.
        //
        // We expose max(|up|,|down|) as `lonePeak` too — it feeds the shape /
        // SNR confidence terms below, where the single largest excursion is the
        // right quantity to compare against the noise floor.
        val rawSpan = (candidate.peakUp - candidate.peakDown)
            .let { if (it < 0f) 0f else it } // guard pathological inputs
        val lonePeak = max(abs(candidate.peakUp), abs(candidate.peakDown))

        // ---- 1. Speed-normalization to the reference speed (R-DET-4) ----
        // The same road feature delivers a larger vertical impulse the faster you
        // hit it. We normalize the measured span back to what it would have been
        // at REFERENCE_SPEED_MPS so the bucket reflects the road, not the drive.
        //
        // MODEL (PROVISIONAL — sqrt scaling):
        //     normalizedSpan = rawSpan * sqrt(v_ref / v)
        // i.e. we assume peak vertical acceleration scales with sqrt(speed).
        //
        // WHY sqrt and not linear: a quarter-car bouncing over a fixed bump
        // profile sees the wheel forced through a fixed vertical displacement in
        // a time that shrinks with speed; the resulting peak accel rises with
        // speed but SUB-linearly once the suspension starts absorbing — empirical
        // dashcam/IMU pothole studies report roughly square-root-ish growth, not
        // linear. Linear (v_ref/v) over-corrects badly at low speed (a 10 km/h
        // crawl would get multiplied 3x and false-promote into Moderate). sqrt is
        // the gentler, safer prior. This exponent is the SINGLE most important
        // thing to fit against the test set — see PROVISIONAL list.
        //
        // We clamp speed into a sane band first: below MIN_NORM_SPEED_MPS the
        // ratio explodes (and such crawl-speed events are dominated by noise
        // anyway — the min-speed GATE lives in RejectionFilter, this is just a
        // numerical floor); above MAX_NORM_SPEED_MPS we stop trusting the model.
        val v = candidate.speedMps.coerceIn(MIN_NORM_SPEED_MPS, MAX_NORM_SPEED_MPS)
        val speedFactor = sqrt(REFERENCE_SPEED_MPS / v)
        val speedNormalizedSpan = rawSpan * speedFactor

        // ---- 2. Apply the per-vehicle calibration scale (R-EXT-1) ----
        // vehicleScale > 1 means THIS car amplifies road input vs the reference
        // vehicle, so the same road produced a bigger raw span here — divide it
        // out to get back to reference-vehicle units. Guard against a zero/garbage
        // scale (treat as "uncalibrated → reference").
        val safeVehicleScale = if (vehicleScale > 1e-3f) vehicleScale else 1f
        val normalizedPeak = speedNormalizedSpan / safeVehicleScale

        // ---- 3. Bucket → Severity (D-011 thresholds) ----
        val severity = bucket(normalizedPeak)

        // ---- 4. Confidence 0..1 (R-EXT-3, D-015) ----
        val confidence = confidence(candidate, lonePeak, calibrationMaturity, gpsAccuracyM)

        // TYPE is no longer decided here (D-040). The HazardClassifier's pitch-couplet
        // fusion is the authoritative breaker/pothole call; finalizeClassification
        // overwrites this field with its verdict (`sev.copy(type = v.type)`) before the
        // Assessment is ever stored or labelled. We emit UNKNOWN as a neutral placeholder
        // so the field stays populated for that copy()/label path.
        return Assessment(
            type = HazardType.UNKNOWN,
            severity = severity,
            confidence = confidence,
            normalizedPeak = normalizedPeak,
        )
    }

    /**
     * Map the normalized peak onto the 3 D-011 buckets. Note "None" (< MINOR
     * floor) is NOT a real bucket — anything that reaches us is above the
     * detection floor — but if a normalized peak somehow lands below the Minor
     * floor we still return MINOR rather than inventing a 4th state, because the
     * Assessment contract has only 3 severities and the store only keeps 1..3.
     */
    private fun bucket(normalizedPeak: Float): Severity = when {
        normalizedPeak >= SEVERE_FLOOR -> Severity.SEVERE
        normalizedPeak >= MODERATE_FLOOR -> Severity.MODERATE
        else -> Severity.MINOR
    }

    /**
     * Confidence in [0,1] (R-EXT-3). A documented multiplicative-then-floored
     * blend of five evidence terms, each itself clamped to [0,1]:
     *
     *   1. SNR proxy      — how far the lone peak stands above the noise floor.
     *   2. shape          — how clean/biphasic the pulse is (a real road event
     *                       swings both ways; a lone spike is suspect).
     *   3. axle-pair      — bonus if a same-shape second pulse corroborated it.
     *   4. calibration    — calibrationMaturity itself, floored (D-015: low early).
     *   5. GPS penalty    — multiplier <1 as accuracy degrades (R-EXT-3).
     *
     * STRUCTURE (PROVISIONAL): we take a WEIGHTED AVERAGE of the first three
     * "morphology quality" terms (SNR, shape, axle bonus), then MULTIPLY by the
     * calibration term and the GPS penalty. WHY this shape:
     *   - The morphology terms are partially redundant evidence about "is this a
     *     real, well-formed road event?" — averaging lets a strong SNR partly
     *     cover a so-so shape, which is what we want.
     *   - Calibration maturity and GPS accuracy are GATING factors, not just more
     *     evidence: if we have no idea how this car translates jolts (maturity≈0)
     *     OR we can't place the hazard (huge GPS error), the assessment is
     *     fundamentally untrustworthy no matter how clean the pulse looked — so
     *     they MULTIPLY the whole thing down. This is exactly D-015's "confidence
     *     starts low when calibration is immature": with maturity 0 the floored
     *     calibration term caps total confidence at CALIB_FLOOR.
     */
    private fun confidence(
        c: DetectionCandidate,
        lonePeak: Float,
        calibrationMaturity: Float,
        gpsAccuracyM: Float,
    ): Float {
        // --- 1. SNR proxy ---
        // The detector keys on a_vert; the gravity-track + sensor noise floor is
        // ~±NOISE_FLOOR_MPS2 (GravityFrame ignores ~150ms transients but jitter
        // remains). Express the peak as a signal-to-noise ratio and squash it: at
        // the noise floor SNR≈1 ⇒ ~0 confidence; well above it ⇒ →1.
        val snr = lonePeak / NOISE_FLOOR_MPS2
        val snrTerm = ((snr - 1f) / (SNR_SATURATION - 1f)).coerceIn(0f, 1f)

        // --- 2. shape cleanliness (biphasic vs lone spike) ---
        // A genuine bump/dip pushes the suspension one way AND lets it rebound
        // the other → both peakUp and peakDown are non-trivial. A lone one-sided
        // spike (door slam, single curb tap, sensor glitch) has energy on only
        // one side. Measure the SMALLER excursion as a fraction of the larger:
        // 1.0 = perfectly biphasic, 0 = purely one-sided. Scale so "mostly
        // biphasic" already reads as good.
        val up = abs(c.peakUp)
        val down = abs(c.peakDown)
        val larger = max(up, down)
        val biphasicRatio = if (larger < 1e-3f) 0f else min(up, down) / larger
        val shapeTerm = (biphasicRatio / SHAPE_FULL_BIPHASIC_RATIO).coerceIn(0f, 1f)

        // --- 3. axle-pair bonus ---
        // A clean second same-shape pulse at the wheelbase delay is strong
        // independent corroboration that this is a real transverse road feature.
        val axleTerm = if (c.axlePairGapMs != null) 1f else 0f

        // Weighted average of the three morphology-quality terms.
        val morphology = (
            W_SNR * snrTerm +
                W_SHAPE * shapeTerm +
                W_AXLE * axleTerm
            ) / (W_SNR + W_SHAPE + W_AXLE)

        // --- 4. calibration maturity (D-015), floored so it never hard-zeros ---
        // Floor keeps a brand-new-but-clean detection from collapsing to exactly
        // 0 (we still want to MAP it, D-015 "map from drive one"); but it stays
        // low — total confidence is capped at ~CALIB_FLOOR while maturity≈0.
        val calibTerm = CALIB_FLOOR +
            (1f - CALIB_FLOOR) * calibrationMaturity.coerceIn(0f, 1f)

        // --- 5. GPS-accuracy penalty (R-EXT-3) ---
        // At/below GPS_GOOD_M we trust localization fully (×1). It ramps linearly
        // down to GPS_PENALTY_FLOOR by the time accuracy degrades to GPS_BAD_M,
        // and never goes below the floor (a hazard with lousy GPS is still a real
        // jolt — we just can't place it well, so we don't zero it out).
        val gpsPenalty = when {
            gpsAccuracyM <= GPS_GOOD_M -> 1f
            gpsAccuracyM >= GPS_BAD_M -> GPS_PENALTY_FLOOR
            else -> {
                val t = (gpsAccuracyM - GPS_GOOD_M) / (GPS_BAD_M - GPS_GOOD_M)
                1f - t * (1f - GPS_PENALTY_FLOOR)
            }
        }

        return (morphology * calibTerm * gpsPenalty).coerceIn(0f, 1f)
    }

    // kotlin.math.min isn't imported as a top-level helper above to keep the
    // import block tight; provide a tiny local alias used only inside this class.
    private fun min(a: Float, b: Float): Float = if (a < b) a else b

    companion object {
        // ============================================================
        //  Speed normalization (R-DET-4) — PROVISIONAL
        // ============================================================
        /** Reference speed the severity table is defined at: 30 km/h = 8.333 m/s
         *  (03-ARCH "Severity buckets @ reference speed 30 km/h"). NOT provisional
         *  — it's the table's definition. */
        const val REFERENCE_SPEED_MPS = 8.333f

        /** PROVISIONAL. Below this speed the v_ref/v ratio blows up and events are
         *  noise-dominated; clamp the divisor here. ~2 m/s ≈ 7 km/h. */
        const val MIN_NORM_SPEED_MPS = 2.0f

        /** PROVISIONAL. Above this speed the sqrt model is untrustworthy
         *  (aerodynamic/secondary-ride effects); clamp. ~33 m/s ≈ 120 km/h. */
        const val MAX_NORM_SPEED_MPS = 33.0f

        // NOTE: the sqrt exponent itself is hard-coded in classify() as sqrt(...).
        // It is the SINGLE most important quantity to fit against the test set —
        // candidates: linear (exp=1.0), sqrt (exp=0.5, current), or a fitted exp.

        // ============================================================
        //  D-011 severity bucket thresholds (m/s², normalized) — PROVISIONAL
        // ============================================================
        // From the 03-ARCH table @ 30 km/h, vehicle-cal applied:
        //   < 1.0 None (not stored)  | 1.0–3.0 Minor | 3.0–6.0 Moderate | >6.0 Severe
        //
        // CALIBRATION NOTE (Session 30e→30f): 53 on-device labels showed a ~9% HOT bias
        // (every user severity correction LOWERED, 4/0) — a +9% floor nudge (3.3/6.6) fit
        // it (12/53→4/53 errors). That nudge was REVERTED once issue E was fixed: the bias
        // is exactly what the per-vehicle VehicleCalibrator.vehicleScale exists to remove
        // (this car reads ~9% high → scale≈1.09 → normalizedPeak = span/scale divides it
        // out). With maturity redefined as EMA convergence, vehicleScale now learns that
        // ~9% within ONE drive instead of never, so a hardcoded global nudge HERE would
        // DOUBLE-correct (~18% → under-classify). Keeping the D-011 table values and
        // letting the per-CAR scale be the single correction path is the principled fix
        // (it adapts to a stiff truck vs a soft SUV; a fixed nudge cannot). RE-CONFIRM
        // severity accuracy on the next labeled drive AFTER calibration has matured.
        /** PROVISIONAL. Moderate starts at 3.0 m/s² normalized peak (D-011). The ~9%
         *  per-vehicle hot bias seen on-device is removed by vehicleScale, not here. */
        const val MODERATE_FLOOR = 3.0f

        /** PROVISIONAL. Severe starts at 6.0 m/s² normalized peak (D-011). */
        const val SEVERE_FLOOR = 6.0f
        // (Minor floor / detection floor of 1.0 lives in EventDetector — anything
        //  reaching us is already above it, so we don't re-gate on it here.)

        // (Type-vote constants removed with classifyType — D-040: HazardClassifier's
        //  pitch-couplet fusion is now the authoritative breaker/pothole call, so the
        //  amplitude-based type votes here were dead. POTHOLE_RISE_MS / the rise-time
        //  discriminator live on in HazardClassifier.)

        // ============================================================
        //  Confidence blend (R-EXT-3, D-015) — PROVISIONAL
        // ============================================================
        /** PROVISIONAL. Effective a_vert noise floor (±). Task brief gives ±0.18
         *  m/s² as the SNR reference; GravityFrame leaves residual jitter. */
        const val NOISE_FLOOR_MPS2 = 0.18f

        /** PROVISIONAL. SNR (peak/noise) at/above which the SNR term saturates to
         *  1.0. ~12× the noise floor ≈ 2.2 m/s² peak reads as "unambiguous". */
        const val SNR_SATURATION = 12.0f

        /** PROVISIONAL. biphasicRatio (smaller/larger excursion) at/above which
         *  the shape term reads as fully clean. 0.6 = the smaller swing is ≥60%
         *  of the larger ⇒ clearly biphasic, not a lone spike. */
        const val SHAPE_FULL_BIPHASIC_RATIO = 0.6f

        // -- morphology-term weights (relative; normalized by their sum) --
        /** PROVISIONAL. Weight on the SNR term — amplitude clarity matters most. */
        const val W_SNR = 0.5f

        /** PROVISIONAL. Weight on the shape (biphasic) term. */
        const val W_SHAPE = 0.3f

        /** PROVISIONAL. Weight on the axle-pair corroboration bonus. */
        const val W_AXLE = 0.2f

        /** PROVISIONAL. Floor on the calibration term so a brand-new, perfectly
         *  clean detection isn't hard-zeroed (we still map it, D-015) — but total
         *  confidence is capped at ~this value while calibrationMaturity≈0, which
         *  is exactly the "confidence starts low" requirement of D-015. */
        const val CALIB_FLOOR = 0.15f

        // -- GPS penalty (R-EXT-3) --
        /** PROVISIONAL. GPS accuracy at/below which localization is "good" (no
         *  penalty). ~8 m matches the G-3 median-localization gate. */
        const val GPS_GOOD_M = 8.0f

        /** PROVISIONAL. GPS accuracy at/above which we apply the full penalty. */
        const val GPS_BAD_M = 30.0f

        /** PROVISIONAL. Multiplier the GPS penalty bottoms out at — never 0,
         *  because a badly-located hazard is still a real jolt worth mapping. */
        const val GPS_PENALTY_FLOOR = 0.4f
    }
}

package com.overdrive.app.roadsense.detect

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Silent, per-vehicle suspension auto-calibration (R-EXT-1, D-014).
 *
 * The same physical bump produces a different vertical jolt in a soft SUV vs a
 * stiff sedan. To make severity a property of the ROAD (not the car), the
 * SeverityClassifier divides the measured impulse by a per-vehicle `vehicleScale`
 * (1.0 = reference vehicle). This class LEARNS that scale continuously from
 * normal driving — no "calibrate now" chore (D-014).
 *
 * ## How it learns
 * On smooth road the vertical residual `a_vert` is just suspension/tyre/road
 * texture noise. Its running RMS over quiet stretches is the vehicle's
 * **baseline roughness response** — a soft car rides floaty (higher baseline),
 * a stiff car transmits more (also higher) … so baseline alone is ambiguous.
 * What we actually want is how the car AMPLIFIES input relative to the reference,
 * which we approximate from the quiet-road residual RMS scaled to a reference
 * RMS. This is a deliberately simple, robust proxy — PROVISIONAL, to be refined
 * against the labeled set; the structure (continuous, quiet-gated, slow EMA) is
 * the deliverable, not the exact transfer model.
 *
 * Only quiet samples feed the estimate — bumps/potholes/events must NOT pollute
 * the baseline, so the caller passes `inEvent` (true while EventDetector is mid
 * event) and we skip those. We also skip when stationary/parked (no road input).
 *
 * ## Maturity
 * `maturity` (0..1) reflects how much quiet driving we've absorbed. It starts at
 * 0 (→ low confidence per D-015, and a red calibration indicator on the overlay,
 * R-OVL-1), climbs toward 1 as samples accumulate, and gates how much we trust
 * `vehicleScale` (we blend from 1.0 toward the learned scale by maturity).
 *
 * Pure, no Android, single-threaded (daemon RoadSense thread). EMA-based → O(1)
 * memory, no buffers.
 */
class VehicleCalibrator(
    /** EMA weight per quiet sample for the baseline RMS. Small → slow, stable
     *  (we want minutes of driving to settle, and immunity to a single rough
     *  patch). ~0.001 at 100 Hz ≈ 10 s time constant on continuous quiet road. */
    private val emaAlpha: Float = DEFAULT_EMA_ALPHA,
    /** Legacy linear horizon — NO LONGER the maturity denominator (maturity is the EMA
     *  convergence fraction now; see [maturity]). Retained only as the absolute ceiling
     *  on a restored quietCount (caps a stale/mismatched-car persisted value). */
    private val maturitySamples: Long = DEFAULT_MATURITY_SAMPLES,
    /** Reference-vehicle quiet-road residual RMS (m/s²). The scale is
     *  learnedRms / referenceRms. PROVISIONAL — fit from a reference device. */
    private val referenceRms: Float = DEFAULT_REFERENCE_RMS,
) {
    /**
     * The three coupled calibration fields, published as ONE immutable object behind
     * a single @Volatile reference (audit concurrency A/B). WHY: the fields are
     * written by the IPC/sensor thread (onSample, 100 Hz) and by the daemon boot
     * thread (restore), and READ by the tick thread (snapshot/persist + overlay
     * maturity). With three loose non-volatile vars there was (A) no happens-before
     * edge so a restore could be invisible to the first onSample → re-seed from 0 and
     * silently lose persisted calibration, and (B) snapshotting quietCount then meanSq
     * as two reads could persist a torn (inconsistent) pair. A single volatile ref to
     * an immutable record gives atomic publication of all three at zero lock cost on
     * the hot path — onSample (single writer) reads the current ref, computes, and
     * swaps in a new one. */
    private data class CalState(val meanSq: Float, val quietCount: Long, val seeded: Boolean)

    @Volatile private var state = CalState(0f, 0L, false)

    /**
     * 0..1 calibration maturity (drives confidence + overlay indicator).
     *
     * DEFINITION (revised — issue E fix, Session 30f): maturity is the EMA's
     * CONVERGENCE FRACTION `1 − (1−α)^N`, not a linear `N / 60_000`. WHY: the baseline
     * RMS is an exponential moving average with weight [emaAlpha]≈0.001, so it is
     * statistically converged after a few TIME CONSTANTS (~1/α ≈ 1000 quiet samples
     * each): ~63% at 1 k, ~86% at 2 k, ~95% at 3 k. Requiring 60 k samples to call it
     * "mature" was meaningless — the estimate stopped moving long before — and on urban
     * roads (only a small fraction of samples pass the quiet gate) 60 k took HOURS, so
     * the overlay sat RED ("Learning your vehicle") essentially forever and confidence
     * stayed pinned at the CALIB_FLOOR (issue E: "calibrating forever"). Tying maturity
     * to the actual convergence of the thing it measures is the honest signal: ORANGE
     * ("improving") by ~415 quiet samples, GREEN ("calibrated", ≥0.67) at ~1108 — roughly
     * one normal drive — and the per-vehicle [vehicleScale] blends in on the SAME
     * schedule, so a real ~9% per-car scale starts correcting severity within a drive
     * instead of never.
     *
     * [maturitySamples] is retained only as the legacy linear scale (and the restore
     * cap); it no longer gates the convergence curve. ALPHA_CONVERGENCE mirrors
     * [emaAlpha] so the curve matches the estimator it describes.
     */
    val maturity: Float
        get() = convergence(state.quietCount)

    /** EMA convergence fraction after [n] quiet samples: 1 − (1−α)^n, clamped 0..1.
     *  This is exactly "how much of the steady-state value the running mean has
     *  absorbed" — the principled meaning of calibration maturity. */
    private fun convergence(n: Long): Float {
        if (n <= 0L) return 0f
        val frac = 1.0 - Math.pow(1.0 - ALPHA_CONVERGENCE, n.toDouble())
        return frac.toFloat().coerceIn(0f, 1f)
    }

    /**
     * The per-vehicle scale the SeverityClassifier consumes. Blended from the
     * reference (1.0) toward the learned ratio by [maturity], so an immature
     * estimate never wildly mis-scales severity — it just gently takes over as
     * confidence in it grows. Clamped to a sane band against a pathological RMS.
     */
    val vehicleScale: Float
        get() {
            val s = state // single volatile read → consistent snapshot
            if (!s.seeded || s.quietCount < MIN_SAMPLES_FOR_SCALE) return 1f
            val rawScale = (sqrt(s.meanSq) / referenceRms).coerceIn(MIN_SCALE, MAX_SCALE)
            // Blend reference(1.0)→learned on the SAME convergence schedule as [maturity]
            // (was the linear N/60k, which kept the learned scale ~nil for hours). Now a
            // real per-car scale takes over as the EMA actually converges (~1 drive).
            val m = convergence(s.quietCount)
            return 1f * (1f - m) + rawScale * m
        }

    /**
     * Feed one vertical-residual sample. Only QUIET, MOVING samples update the
     * baseline; events and near-stationary samples are skipped so they can't
     * corrupt the vehicle model.
     *
     * @param aVert    vertical residual from GravityFrame (m/s²).
     * @param speedKmh current speed; below a floor we're not getting road input.
     * @param inEvent  true while EventDetector is accumulating an event — skip.
     */
    /**
     * @param eventsPerSec recent detection rate (from the controller's washboard
     *   window). A sustained rough/washboard stretch fires events continuously;
     *   such road is NOT "quiet" even though individual samples sit under the
     *   per-sample ceiling, so we must NOT learn from it or the baseline inflates
     *   and severities get under-reported (audit detection #5).
     */
    fun onSample(aVert: Float, speedKmh: Float, inEvent: Boolean, eventsPerSec: Float = 0f) {
        if (inEvent) return
        if (speedKmh < MIN_CALIBRATION_SPEED_KMH) return
        // Don't learn on a rough/eventful stretch — only genuinely quiet road.
        if (eventsPerSec > MAX_CALIBRATION_EVENT_RATE) return
        // Reject samples that are themselves bump-sized — even outside a formally
        // detected event, a big transient must not enter the "quiet road" model.
        if (abs(aVert) > QUIET_SAMPLE_CEILING) return

        val sq = aVert * aVert
        // Single writer (the IPC/sensor thread). Read the current immutable state,
        // compute the next, publish atomically via the volatile swap.
        val s = state
        val newMeanSq = if (!s.seeded) sq else s.meanSq + emaAlpha * (sq - s.meanSq)
        state = CalState(newMeanSq, s.quietCount + 1, true)
    }

    fun reset() {
        state = CalState(0f, 0L, false)
    }

    /**
     * Restore previously-learned state (cross-restart persistence). The per-vehicle
     * suspension model is a property of the CAR, not a drive — it must accumulate
     * across daemon restarts / reboots / app updates, otherwise a head unit that
     * power-cycles with the car would reset to "Calibrating" every trip and never
     * reach maturity. The controller saves the snapshot to UCM and restores here on
     * start. Defensive clamps: a corrupt/negative value starts fresh; a persisted
     * quietCount is CAPPED at [maturitySamples] so a stale-but-huge value (e.g. from
     * a different car) can't pin maturity beyond fully-mature (audit accuracy S2).
     */
    fun restore(savedQuietCount: Long, savedMeanSq: Float) {
        if (savedQuietCount <= 0L || savedMeanSq < 0f || savedMeanSq.isNaN()) return
        state = CalState(savedMeanSq, savedQuietCount.coerceAtMost(maturitySamples), true)
    }

    /** Atomic snapshot of (quietCount, meanSq) for persistence — one volatile read,
     *  so the persisted pair is always internally consistent (audit concurrency B). */
    fun snapshot(): Pair<Long, Float> = state.let { it.quietCount to it.meanSq }

    companion object {
        const val DEFAULT_EMA_ALPHA = 0.001f
        /** Convergence-rate used by [maturity]/[vehicleScale] (= the EMA weight). Kept
         *  as its own const so maturity tracks the estimator's actual settling even if
         *  [emaAlpha] is ever made instance-tunable. At 0.001: ~63% converged @1k quiet
         *  samples, ~95% @3k — maturity reaches GREEN (~0.67) in roughly one drive. */
        const val ALPHA_CONVERGENCE = 0.001
        /** Legacy linear horizon. NO LONGER gates maturity (that's the convergence
         *  curve now); retained only as the absolute cap on a restored quietCount so a
         *  stale-but-huge persisted value can't run the convergence curve past 1.0 or
         *  pin a mismatched car (audit accuracy S2). ~10 min of cumulative quiet @100Hz. */
        const val DEFAULT_MATURITY_SAMPLES = 60_000L
        /** PROVISIONAL reference quiet-road RMS — fit from a reference vehicle. */
        const val DEFAULT_REFERENCE_RMS = 0.18f // seeded from F-005 noise floor; tune
        /** Don't emit a non-1.0 scale until we've seen at least this many quiet
         *  samples (~10 s at 100 Hz) — avoids wild early ratios. */
        const val MIN_SAMPLES_FOR_SCALE = 1_000L
        const val MIN_SCALE = 0.4f
        const val MAX_SCALE = 2.5f
        /** Below this speed there's no meaningful road input to learn from. */
        const val MIN_CALIBRATION_SPEED_KMH = 15f
        /** A_vert above this isn't "quiet road" — exclude from the baseline. */
        const val QUIET_SAMPLE_CEILING = 0.9f
        /** Above this detection rate the road is rough/eventful, not quiet — freeze
         *  calibration so a washboard/cobble stretch can't inflate the baseline. */
        const val MAX_CALIBRATION_EVENT_RATE = 0.3f
    }
}

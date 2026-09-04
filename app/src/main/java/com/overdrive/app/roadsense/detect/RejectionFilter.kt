package com.overdrive.app.roadsense.detect

import kotlin.math.abs

/**
 * Rolling gyro statistics over the event window, pre-summarized.
 *
 * WHY a pre-summarized struct instead of `List<ImuGyroSample>`:
 * the detection hot path runs per-event at ~200 ms resolution and the gyro
 * arrives at 99.9 Hz (F-006) — handing a fresh sublist to [RejectionFilter] on
 * every candidate means an allocation + a min/max scan inside the rejection
 * path. The owning [RoadSenseService] already keeps a streaming sliding window
 * over the IMU ring buffer (R-PERF-3); it can maintain these two peaks for free
 * as samples arrive and snapshot them at event time. So we keep [evaluate]
 * allocation-free and O(1), and push the cheap rolling work to where the samples
 * already flow.
 *
 * Both values are PEAK ABSOLUTE rates over the window (sign discarded — we only
 * care "how hard is the body rotating", not which way).
 *
 *  - [peakYawRateRps]  : peak |yaw rate| (rad/s). Yaw = cornering. This is the
 *                        gyro corroboration for the steering-angle reject (F-006:
 *                        gyro saw 0.088→0.347 rad/s on a hard turn). The device
 *                        frame is tilted ~9.2° (F-005) so a "yaw" axis isn't
 *                        perfectly isolated; the service should feed the largest
 *                        horizontal-plane rotation rate here. For rejection
 *                        purposes the exact axis split doesn't matter — any large
 *                        sustained rotation during a vertical jolt means the body
 *                        is turning, not hitting a transverse road feature.
 */
data class GyroStats(
    val peakYawRateRps: Float,
)

/**
 * Decides whether a [DetectionCandidate] is a real road hazard or a driver-/
 * vehicle-caused artefact that must NOT be mapped or warned on.
 *
 * This is THE component that has to pass gate **G-4** (zero false warnings from
 * braking/cornering on the adversarial drive — hard-brake ×10, sharp-turn ×10).
 * It implements R-DET-5 (reject braking, cornering, expansion joints,
 * cobblestone/washboard, curb strikes, door slams), R-DET-8 / D-018
 * (vehicle-dynamics fusion), using the BYD bus snapshot ([VehicleDynamics],
 * F-011) plus pre-summarized gyro ([GyroStats], F-006).
 *
 * ## Precision vs recall stance
 * G-4 makes false positives from brake/turn the cardinal sin, so when the
 * vehicle bus *clearly* says "the driver did this" (brake mashed, big steering,
 * launch) we lean toward rejecting even at some recall cost — those signals are
 * direct measurements of driver input, not inference. BUT the per-phase recall
 * gates (G-1: ≥90% breaker / ≥75% pothole recall) still matter, so the rules are
 * structured so each individual reject needs a *strong, specific* trigger; we do
 * NOT reject on weak or stale evidence. Where a threshold trades precision for
 * recall is called out at each const.
 *
 * ## Staleness (the central nuance, F-011 / D-018 caveat)
 * brake% / accel% / gear are ~5 s polls in the current collector; speed +
 * steering are low-latency push listeners. [VehicleDynamics.brakeAgeMs] is the
 * age of the brake/accel/gear poll. A 4-second-old "brake 90%" tells us nothing
 * about *this* 180 ms jolt, so we only act on pedal/gear evidence when it is
 * fresh ([STALE_PEDAL_MS]). For cornering, steering is push (fresh) AND gyro
 * corroborates, so cornering rejection survives even when the pedal poll is
 * stale — that is exactly why we carry gyro as an independent witness.
 *
 * Pure logic. No Android imports, no coroutines, jvmTarget 11. [evaluate] is
 * fully STATELESS — every input (including the washboard event-rate) is passed
 * in. Rolling state (gyro peaks, recent event rate) is maintained by the caller,
 * by design, so this class stays trivially unit-testable against the labeled
 * test set (R-DET-7).
 *
 * Every threshold here is PROVISIONAL and WILL be tuned against the labeled
 * test-drive set (R-DET-7, G-1..G-4). They are named consts precisely so tuning
 * is a one-line edit per knob, not a logic rewrite.
 */
class RejectionFilter(
    /**
     * Minimum path radius (m) a rotation is allowed to imply before it's called
     * "cornering". The speed-adaptive yaw bar is `ω = speedMps / cornerMinRadiusM`
     * (clamped) — i.e. reject when the body is turning tighter than this radius.
     * Smaller ⇒ only very tight turns reject (more recall, less cornering rejection);
     * larger ⇒ gentler curves also reject. Default 40 m reproduces the legacy flat
     * 0.25 rad/s bar at ~36 km/h, so an un-tuned install is unchanged in the cruise
     * band; only the low- and high-speed tails shift in the recall-safe direction.
     */
    private val cornerMinRadiusM: Float = DEFAULT_CORNER_MIN_RADIUS_M,
    /**
     * Floor of the speed-adaptive yaw bar (rad/s). At crawl speed `v / R` would
     * approach 0 and reject almost any wobble; the floor keeps the bar at a sane
     * minimum (≈ the gentle-turn rate) so steady-line crawl jitter isn't vetoed.
     */
    private val yawRejectFloorRps: Float = DEFAULT_YAW_REJECT_FLOOR_RPS,
    /**
     * Ceil of the speed-adaptive yaw bar (rad/s). Caps the bar at high speed so it
     * can never rise so far that a genuine committed turn slips under it. Set at/just
     * below F-006's measured committed-turn rate (~0.347) so a real fast corner is
     * always still rejected (G-4).
     */
    private val yawRejectCeilRps: Float = DEFAULT_YAW_REJECT_CEIL_RPS,
    /**
     * |steering angle| (deg) above which cornering is called from the steering
     * witness alone (speed-invariant — direct driver input). Constructor-injected
     * only so it's configurable alongside the yaw knobs; default unchanged.
     */
    private val steeringRejectDeg: Float = STEERING_REJECT_DEG,
    /**
     * When false, the speed-adaptive yaw bar is bypassed and Rule 3 uses the legacy
     * flat [YAW_RATE_REJECT_RPS] — a one-flag kill switch back to exact pre-change
     * behaviour if a marked drive ever shows a regression.
     */
    private val speedAdaptiveYaw: Boolean = true,
) {

    /**
     * Speed-adaptive cornering-reject yaw threshold (rad/s) for the given speed.
     * `ω = v / R_min`, clamped to [floor, ceil]. With [speedAdaptiveYaw] off, returns
     * the legacy flat [YAW_RATE_REJECT_RPS] so behaviour is bit-identical. Pure;
     * speedKmh is the EVENT-time speed (see [eventSpeedKmh]) since ω ∝ v must use the
     * speed at the jolt, not a stale cached poll.
     */
    fun yawRejectThreshold(speedKmh: Float): Float {
        if (!speedAdaptiveYaw) return YAW_RATE_REJECT_RPS
        val v = (speedKmh / 3.6f).coerceAtLeast(0f)            // m/s
        val r = cornerMinRadiusM.coerceAtLeast(1f)             // guard /0
        return (v / r).coerceIn(yawRejectFloorRps, yawRejectCeilRps)
    }

    /**
     * Speed to use for the ω ∝ v comparison: the EVENT-time speed carried on the
     * candidate ([DetectionCandidate.speedMps], captured at the defining peak), which
     * is the physically-correct instant for a rate test. Falls back to the (possibly
     * ~stale) vehicle-bus speed only if the candidate didn't carry one.
     */
    private fun eventSpeedKmh(candidate: DetectionCandidate, dynamics: VehicleDynamics): Float =
        if (candidate.speedMps > 0f) candidate.speedMps * 3.6f else dynamics.speedKmh

    /**
     * @param candidate     the raw event from [EventDetector] (morphology only).
     * @param dynamics      latest BYD vehicle-dynamics snapshot (F-011, D-018).
     *                      Each pedal/gear field is discounted by [VehicleDynamics.brakeAgeMs].
     * @param recentGyro    pre-summarized gyro peaks over the event window (F-006),
     *                      maintained by the caller (see [GyroStats]).
     * @param eventsPerSec  rolling rate of *accepted-shape* candidates over the
     *                      last ~[WASHBOARD_WINDOW_S] seconds, maintained by the
     *                      caller. WHY a plain Float param and not internal state:
     *                      keeping [evaluate] stateless means the same instance is
     *                      safe to share across threads/tests, and the service
     *                      already counts candidates as it emits them — it is the
     *                      natural owner of the window. A continuous spray of
     *                      events == washboard/cobblestone chatter (R-DET-5), which
     *                      no single candidate can reveal. Default 0f so callers /
     *                      tests that don't track it simply never trip the
     *                      washboard rule.
     *
     * @return a [RejectionVerdict]. `rejected=false, reason=null` means "passes —
     *         hand to the classifier". `rejected=true` carries a short machine
     *         reason string (see each rule).
     */
    fun evaluate(
        candidate: DetectionCandidate,
        dynamics: VehicleDynamics,
        recentGyro: GyroStats,
        eventsPerSec: Float = 0f,
        /** When true (primary HazardClassifier path, D-038/D-040), SKIP the hard brake/accel
         *  vetoes (Rules 1 & 2) and let the classifier weigh pedal input as a SOFT artifact
         *  term gated by road-cue presence instead. This is the fix for "braking INTO a real
         *  breaker": the driver slows for the breaker (brake>25%), so the hard brake veto would
         *  drop exactly the hazard the user cares about — but a real breaker ALSO has a clean
         *  pitch couplet + biphasic, which the classifier's artifact penalty checks for, so it
         *  keeps the breaker while still suppressing a pure brake-dive (pedal high, no road
         *  cue). The CONTEXT vetoes (gear, cornering, washboard, curb) still apply — they're
         *  unambiguous regardless of road feature. Default false = legacy hard-reject behaviour
         *  (existing callers/tests unchanged). */
        softPedal: Boolean = false,
    ): RejectionVerdict {

        // ── Rule 4 (checked FIRST): not driving forward ──────────────────────
        // A jolt while in P/R/N is a parking maneuver, a reverse bump, or the car
        // sitting still — never a road hazard we want to map (R-DET-8: "gear not
        // in forward drive ⇒ don't map"). We check this before the pedal rules
        // because it is the cheapest, highest-confidence veto and doesn't depend
        // on a fresh poll being a *jolt* cause — it's a context gate.
        //
        // Staleness: gear is a ~5 s poll, BUT gear state is SLOWLY-VARYING — you sit in
        // D for minutes, in P while parked — so a stale "P/R/N" reading is still
        // trustworthy "now", unlike a stale pedal %. So the gear veto gets its own,
        // larger staleness budget [STALE_GEAR_MS] instead of the tight pedal budget.
        // This matters MORE since Session-34 removed the EventDetector speed floor: the
        // forward-gear check is now the PRIMARY context guard against parking-lot /
        // reverse / driveway / door-slam jolts at crawl speed (which the speed floor used
        // to mask). Keying it on the 1.5 s pedal budget left it usually-stale → silently
        // passing, so those low-speed artefacts would have leaked through once the floor
        // was gone. The larger budget closes that hole while still ignoring a genuinely
        // ancient poll. Precision/recall: protects precision at crawl (drops parking
        // artefacts by CONTEXT, not speed) while a forward-gear crawl over a real breaker
        // still passes — exactly the no-speed-cap behaviour we want.
        if (dynamics.brakeAgeMs < STALE_GEAR_MS && !dynamics.isForwardDrive) {
            return RejectionVerdict(true, "not_forward_gear")
        }

        // ── Rule 1: braking dive ─────────────────────────────────────────────
        // Hard braking pitches the nose down then rebounds — a vertical transient
        // that looks bump-like. If the brake pedal is meaningfully pressed AND the
        // reading is fresh, attribute the jolt to the driver, not the road
        // (R-DET-8). This is a PRIMARY G-4 defense (hard-brake ×10).
        // Precision/recall trade: a low brake threshold (25%) deliberately favors
        // precision/G-4 — light brake-and-bump overlaps will be dropped. That is
        // acceptable: the same hazard is almost always re-encountered without the
        // brake press and mapped then (D-015 "map from drive one" + repeats).
        if (!softPedal &&
            dynamics.brakeAgeMs < STALE_PEDAL_MS &&
            dynamics.brakePercent > BRAKE_REJECT_PERCENT
        ) {
            return RejectionVerdict(true, "braking")
        }

        // ── Rule 2: launch squat ─────────────────────────────────────────────
        // Hard acceleration squats the rear and unloads the front — same artefact
        // class as braking, opposite sign (R-DET-8 launch squat). Threshold is set
        // higher than brake (40 vs 25) because moderate throttle is normal cruising
        // and we must not reject genuine bumps taken under light accel; only an
        // aggressive launch produces a squat transient worth vetoing.
        // Precision/recall: higher bar than brake protects recall during normal
        // throttle; still catches the aggressive launch that actually fakes a jolt.
        if (!softPedal &&
            dynamics.brakeAgeMs < STALE_PEDAL_MS &&
            dynamics.accelPercent > ACCEL_REJECT_PERCENT
        ) {
            return RejectionVerdict(true, "accelerating")
        }

        // ── Rule 3: cornering body-roll ──────────────────────────────────────
        // A turn rolls the body, which the tilt-corrected vertical channel partly
        // picks up as a jolt. TWO independent witnesses (R-DET-8 "corroborates
        // gyro"), and we reject on EITHER because they have complementary failure
        // modes:
        //   • steering angle (push listener, low-latency F-011) — direct driver
        //     input. |deg| large ⇒ turning.
        //   • gyro yaw rate (F-006, independent of the vehicle bus) — the body is
        //     actually rotating. Survives even if steering were momentarily stale,
        //     and catches roll from e.g. a fast lane change the wheel angle
        //     understates.
        // This is the OTHER primary G-4 defense (sharp-turn ×10). Using OR (not
        // AND) leans toward rejection on cornering — intentional per the G-4 bias.
        // Precision/recall: OR + relatively low gyro/steering thresholds favors
        // precision/G-4 at the cost of dropping bumps taken mid-gentle-curve; real
        // straight-line hazards (the common case) are unaffected, and curve
        // hazards re-map on a straighter pass.
        // SPEED-ADAPTIVE yaw gate (the user's "higher gyro threshold at high speed,
        // lower at low speed"): the yaw rate a GENUINE corner produces is ω = v / R
        // (path radius R), so the SAME corner spins the body FASTER at high speed and
        // SLOWER at low speed. A flat 0.25 rad/s bar therefore MISSES tight LOW-speed
        // turns (a 15 m turn at 10 km/h is only 0.185 rad/s < 0.25 → leaks through).
        // yawRejectThreshold() compares against ω = v / cornerMinRadiusM instead — i.e.
        // reject when the body is turning TIGHTER than R_min — a bar that scales with
        // speed (clamped to [floor, ceil]). This is NOT the Session-32 accel mistake:
        // that was an OPEN/detect threshold (raising it with speed suppressed real
        // bumps); this is a VETO threshold.
        //
        // G-4 GUARD (default ceil = legacy 0.25): the ceil caps the bar at the OLD flat
        // value, so by default the adaptive bar can only ever reject cornering MORE than
        // before (at low speed, where it drops toward the floor) and NEVER less (at high
        // speed it sits exactly at the legacy 0.25). That deliberately ships only the
        // SAFE half of the request — the low-speed tightening — because raising the
        // high-speed bar above 0.25 would open a pass-band letting real high-speed
        // sweeping curves (R≈52–72 m @65 km/h, ~0.5 g) leak past the veto. Raising
        // cornerYawCeilRps (≤0.347) to enable the high-speed half is opt-in and must be
        // marked-drive validated (sharp-turn ×10 at varied speeds) first. Straight-line
        // bumps are unaffected at any speed: their gravity-projected yaw is ≈0 (the
        // alongGravity isolation in RoadSenseController.onGyro), well under the floor.
        // The STEERING_REJECT_DEG witness (direct driver input, speed-invariant) is an
        // independent OR-arm that still catches committed turns regardless of speed.
        val yawBar = yawRejectThreshold(eventSpeedKmh(candidate, dynamics))
        if (abs(dynamics.steeringAngleDeg) > steeringRejectDeg ||
            recentGyro.peakYawRateRps > yawBar
        ) {
            return RejectionVerdict(true, "cornering")
        }

        // ── Rule 5: washboard / cobblestone chatter ──────────────────────────
        // Cobblestone / washboard / a bad expansion-joint stretch produces a
        // CONTINUOUS train of small events, not one isolated hazard (R-DET-5). No
        // single candidate can reveal this — only the rate across a window can. If
        // accepted-shape candidates are arriving faster than a real road can
        // present distinct hazards, treat the whole burst as surface texture and
        // reject (the caller suppresses warnings for the burst rather than
        // chiming on every cobble).
        // Precision/recall: this is a precision guard against a fixed-rate texture
        // spammer. Threshold is per-second so it scales with the window the caller
        // chose ([WASHBOARD_WINDOW_S]); set above the rate at which two *genuine*
        // separate hazards could plausibly occur back-to-back, to avoid dropping
        // two real bumps that happen to be close.
        if (eventsPerSec > WASHBOARD_MAX_EVENTS_PER_SEC) {
            return RejectionVerdict(true, "washboard")
        }

        // ── Rule 6: curb strike / door slam (lone short one-sided spike) ──────
        // THE TENSION (documented per the brief): real potholes are frequently
        // single-axle and one-sided too, so an over-eager "single one-sided hit"
        // reject would gut pothole recall (G-1 potholes ≥75% — already the hardest
        // gate). A curb strike or door slam, by contrast, is distinguished by being
        // BOTH:
        //   (a) morphologically EXTREME-yet-SHORT — a sharp impulse with a very
        //       brief duration (< [CURB_MAX_DURATION_MS]); a pothole at road speed
        //       has a longer load→drop→rebound span (F-007: a speed bump alone is
        //       ~180 ms), and
        //   (b) fully ONE-SIDED (lateral asymmetry ≈ 1) with NO axle-pair partner
        //       (axlePairGapMs == null) — a transverse road feature hits both
        //       wheels/axles; a kerb scuff or a door slam does not.
        // We require ALL of (no partner) AND (extreme asymmetry) AND (very short)
        // before rejecting — deliberately CONSERVATIVE, because the cost of a false
        // reject here is a missed real pothole. We would rather pass a borderline
        // curb strike (it'll fail consensus / never repeat, R-CRD-3) than suppress
        // a genuine pothole.
        // Precision/recall: this rule is tuned hard toward RECALL (pass when in
        // doubt). It only fires on the unambiguous lone-short-one-sided signature.
        // Gate on asymmetryValid: the one-sided test is meaningless until the
        // horizontal-accel stage actually measures lateralAsymmetry. Without this
        // guard `oneSided` is always false (hardcoded 0) so this rule was dead code;
        // WITH a future always-1 bug it'd over-reject. Only apply when measured
        // (audit detection #1).
        val noAxlePartner = candidate.axlePairGapMs == null
        val veryShort = candidate.durationMs < CURB_MAX_DURATION_MS
        val oneSided = candidate.asymmetryValid && candidate.lateralAsymmetry > CURB_MIN_ASYMMETRY
        if (noAxlePartner && veryShort && oneSided) {
            return RejectionVerdict(true, "curb_or_slam")
        }

        // Passed every gate — hand to the classifier.
        return RejectionVerdict(false, null)
    }

    companion object {
        // ─────────────────────────────────────────────────────────────────────
        // ALL VALUES PROVISIONAL — tune against the labeled test set (R-DET-7,
        // gates G-1..G-4). Each comment notes its precision↔recall lean.
        // ─────────────────────────────────────────────────────────────────────

        /**
         * Max age (ms) a brake/accel/gear poll may have before we stop trusting it
         * to describe "now". F-011 says these are ~5 s polls; a value old relative
         * to a ~180 ms event is meaningless. 1500 ms is provisional — short enough
         * that the reading plausibly overlaps the event, generous enough to catch a
         * poll that landed ~1 s before. Once RoadSense drives a faster poll
         * (R-PERF-4) this can drop toward the new poll interval.
         * Lean: smaller = more recall (fewer stale-driven rejects), larger = more
         * precision (trust older pedal evidence). 1500 ms is a middle bias.
         */
        const val STALE_PEDAL_MS = 1500L

        /**
         * Max age (ms) a GEAR reading may have before the forward-drive veto stops
         * trusting it. MUCH larger than [STALE_PEDAL_MS] because gear is slowly-varying
         * (minutes in D, parked in P) — a 5 s-old "P/R/N" still describes "now", whereas a
         * 5 s-old "brake 90%" does not. 6000 ms comfortably spans the ~5 s gear poll so the
         * veto is reliably available (not silently stale), which is essential now that the
         * gear gate is the PRIMARY low-speed parking-artefact guard (Session-34 removed the
         * EventDetector speed floor). Lean: PRECISION at crawl, without a speed cap. */
        const val STALE_GEAR_MS = 6000L

        /**
         * Brake pedal % above which a coincident jolt is attributed to braking
         * dive, not the road (R-DET-8). Provisional 25 — low on purpose: G-4
         * (zero brake-induced false warnings) is the cardinal gate, and a real
         * brake-and-bump re-maps later without the brake. Lean: PRECISION/G-4.
         */
        const val BRAKE_REJECT_PERCENT = 25

        /**
         * Accelerator % above which a coincident jolt is attributed to launch
         * squat (R-DET-8). Provisional 40 — higher than brake because moderate
         * throttle is normal cruising; only an aggressive launch fakes a jolt.
         * Lean: protects RECALL under normal throttle while still catching launches.
         */
        const val ACCEL_REJECT_PERCENT = 40

        /**
         * |steering angle| (deg) above which a coincident jolt is attributed to
         * cornering body-roll (R-DET-8). Range is ±780° (F-011). Provisional 90 —
         * roughly a deliberate turn, well above lane-keeping wiggle. Lean:
         * PRECISION/G-4; drops bumps taken mid-real-turn (acceptable, re-maps
         * straight).
         */
        const val STEERING_REJECT_DEG = 90f

        /**
         * Peak |yaw rate| (rad/s) above which the gyro independently calls
         * "cornering" (F-006: hard turn ramped to 0.347 rad/s, range seen ±0.35).
         * Provisional 0.25 — comfortably above at-rest noise (~1e-3) and gentle
         * drift, below a committed turn, so it fires on real cornering but not
         * straight-line driving. Lean: PRECISION/G-4 (the gyro witness exists to
         * catch turns steering might understate, e.g. fast lane changes).
         */
        const val YAW_RATE_REJECT_RPS = 0.25f

        // ── Speed-adaptive cornering-reject yaw bar (ω = v / R_min, clamped) ──────
        // The user's "higher gyro threshold at high speed, intelligently lower at low
        // speed". A genuine corner's yaw rate is ω = v / R, so we reject when the body
        // is turning tighter than R_min — a bar that RISES with speed (the requested,
        // and physically-correct, behaviour). ALL PROVISIONAL, physics-seeded (F-006:
        // at-rest ~1e-3, hard turn ramped 0.088→0.347 rad/s).
        //
        // DEFAULT IS MONOTONE-SAFE (G-4): the ceil is the LEGACY flat bar (0.25), so the
        // adaptive bar NEVER rises above 0.25 — it can only reject cornering MORE than
        // before (at low speed), never LESS (at high speed). This is the key G-4 guard:
        // raising the high-speed bar above 0.25 would open a pass-band where real
        // high-speed sweeping curves (e.g. R≈52–72 m at 65 km/h, ~0.5 g) leak past the
        // cornering veto and a roll-contaminated transient gets mapped — a false-warning
        // regression. So by default we ship ONLY the safe half of the request: the
        // LOW-speed tightening (bar drops below 0.25 toward the floor, catching tight
        // crawl turns the old flat 0.25 missed; straight low-speed bumps have ≈0
        // gravity-projected yaw so they are NOT affected). To ALSO get the high-speed
        // "higher bar" half, raise roadSense.cornerYawCeilRps (e.g. 0.30–0.347) and
        // VALIDATE on a marked sharp-turn-×10-at-varied-speeds drive first (the codebase
        // rule: no detection-threshold change that could regress G-4 ships unvalidated).
        //
        // DEFAULT IDENTITY: at R_min=40 m the bar = 0.25 rad/s at v=10 m/s (36 km/h),
        // and the 0.25 ceil holds for every speed ≥36 km/h — so the cruise/highway band
        // is byte-for-byte the legacy behaviour. Worked bar(speed): 0.12 (floor)
        // ≤17.3 km/h; 0.139 @20; 0.25 @36; 0.25 (ceil) ≥36 km/h.

        /** Min path radius (m) a rotation may imply before it's "cornering". 40 m
         *  reproduces the legacy 0.25 rad/s bar at 36 km/h. */
        const val DEFAULT_CORNER_MIN_RADIUS_M = 40f

        /** Floor of the adaptive yaw bar (rad/s) — the bar at crawl speed. ≈ a gentle
         *  turn rate; keeps steady-line crawl jitter from being vetoed while still
         *  catching a genuinely tight low-speed turn (a 15 m turn at 10 km/h = 0.185 >
         *  0.12, so it rejects — BETTER than the old flat 0.25 which missed it). */
        const val DEFAULT_YAW_REJECT_FLOOR_RPS = 0.12f

        /** Ceil of the adaptive yaw bar (rad/s). Capped at the LEGACY flat bar (0.25)
         *  so the adaptive bar can never reject LESS cornering than before at high
         *  speed — the G-4 invariant. Raising this (≤ F-006's 0.347 committed-turn
         *  rate) is the opt-in "higher bar at high speed" half of the request and MUST
         *  be marked-drive-validated before shipping as a default. */
        const val DEFAULT_YAW_REJECT_CEIL_RPS = 0.25f

        /**
         * Window (seconds) the caller computes [evaluate]'s `eventsPerSec` over.
         * Documented here so the rate threshold and the caller's window stay a
         * matched pair. ~2 s per R-DET-5's "cluster" notion. Not consumed by
         * [evaluate] directly (the rate is pre-divided) — it's the contract.
         */
        const val WASHBOARD_WINDOW_S = 2.0f

        /**
         * Accepted-shape events/sec above which the burst is treated as
         * washboard/cobblestone surface texture, not distinct hazards (R-DET-5).
         * Provisional 1.5 /s ≈ 3 events in the 2 s window. Set above the rate two
         * *genuine* separate hazards could realistically occur back-to-back so we
         * don't drop two close real bumps. Lean: PRECISION guard; raise if it ever
         * eats legitimately dense real hazards, lower if cobblestone leaks through.
         */
        const val WASHBOARD_MAX_EVENTS_PER_SEC = 1.5f

        /**
         * Duration (ms) below which an isolated one-sided hit is short enough to
         * look like a curb scuff / door slam impulse rather than a road-speed
         * pothole (F-007: a speed bump alone spans ~180 ms; a pothole load→drop→
         * rebound is comparable or longer). Provisional 60 — deliberately tight so
         * we only catch the sharp-impulse signature. Lean: RECALL (longer real
         * potholes are never touched by this rule).
         */
        const val CURB_MAX_DURATION_MS = 60

        /**
         * Lateral asymmetry (0..1, [DetectionCandidate.lateralAsymmetry]) above
         * which a hit is "fully one-sided" — a precondition for the curb/slam
         * reject. The ratio is horizPeak/(horizPeak+vertPeak); because body-roll
         * lateral g is a FRACTION of the vertical jolt, even a pure one-sided hit
         * rarely exceeds ~0.5, so the old 0.85 (horizontal ~5.7× vertical) was
         * physically unreachable and left Rule 6 effectively dead (audit detection
         * #5). 0.5 (≈ horizontal as large as vertical — only a near-curb-tap kick,
         * not a road jolt) keeps the reject reachable yet still extreme, so it
         * fires on a genuine kerb/door-slam without gutting single-wheel pothole
         * recall. Combined with the no-partner + very-short conditions this keeps
         * Rule 6 conservative. Lean: RECALL. PROVISIONAL — fit on the labeled set.
         */
        const val CURB_MIN_ASYMMETRY = 0.5f
    }
}

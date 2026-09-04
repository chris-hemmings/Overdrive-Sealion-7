package com.overdrive.app.roadsense.store

import com.overdrive.app.roadsense.detect.HazardType
import com.overdrive.app.roadsense.detect.Pose
import com.overdrive.app.roadsense.detect.StoredHazard
import com.overdrive.app.roadsense.detect.altitudeMatches
import kotlin.math.abs
import kotlin.math.cos

/**
 * Given the live vehicle [Pose] and the hazards in nearby tiles, answer the
 * question the overlay + warning layer keep asking: **"what's the next hazard
 * ahead, how far, and in which direction?"** (R-OVL-2, R-EXT-4).
 *
 * Pure ranking/geometry — no DB, no Android, no clock. The caller fetches
 * candidate rows from [RoadSenseStore.queryAhead] (tile-scoped, cheap) and hands
 * them here; this stage does the precise metric + bearing-cone filtering that is
 * wasteful to express in SQL. Keeping it pure makes the "is it ahead / how far"
 * logic unit-testable, which is where direction-aware warnings live or die.
 *
 * ## What "ahead" means + "our road"
 * A hazard is *ahead* if the bearing FROM the car TO the hazard is within
 * [forwardConeDeg] of the car's travel bearing. This is the R-EXT-4 direction
 * filter: a pothole 8 m away in the opposite carriageway is behind the cone and
 * must NOT warn. We also drop anything farther than [maxRangeM] (out of interest)
 * and — by default — anything already essentially under the car (< [minRangeM],
 * too late to warn, avoids a "0 m" flicker).
 *
 * The forward cone keeps things geometrically ahead, but that alone can still leak
 * a hazard on a PARALLEL/opposite/crossing road that happens to fall in the cone at
 * a bend or junction. So we ALSO require a ROAD MATCH: the hazard's stored detection
 * heading must align with our travel heading in the SAME direction within
 * [headingMatchDeg] (the opposite carriageway is NOT accepted — R-EXT-4). And when
 * the heading is unreliable (crawl / no GPS bearing) we suppress entirely rather than
 * warn omnidirectionally. This guarantees "warn only for the road we're driving, in
 * our direction," not the next street over or the oncoming lane.
 *
 * Heading sanity: at very low speed GPS bearing is noisy/garbage, so the caller
 * passes whether the heading is trustworthy; when it isn't we SUPPRESS entirely
 * (rank returns empty) rather than warn omnidirectionally — without a reliable
 * heading we can't tell our road/direction from the opposite carriageway or a
 * cross street, and at crawl speed the driver can already see the bump (R-EXT-4).
 */
class ApproachEngine(
    private val forwardConeDeg: Double = DEFAULT_FORWARD_CONE_DEG,
    private val maxRangeM: Double = DEFAULT_MAX_RANGE_M,
    private val minRangeM: Double = DEFAULT_MIN_RANGE_M,
    private val zoneGapM: Double = DEFAULT_ZONE_GAP_M,
    private val headingMatchDeg: Double = DEFAULT_HEADING_MATCH_DEG,
) {

    /** The "already under the car / too late to warn" range floor (m), read-only, so the
     *  WarningCoordinator's sticky-visual latch uses the SAME passed-hazard threshold this
     *  engine applies in rank() rather than duplicating the constant. */
    val minRangeMeters: Double get() = minRangeM

    /**
     * One hazard resolved relative to the car: how far, and the turn-relative
     * bearing for the overlay's direction arrow.
     *
     * @param rangeM            great-circle distance car→hazard (m).
     * @param relativeBearingDeg bearing relative to travel direction, −180..+180;
     *                           0 = dead ahead, + = to the right, − = to the left.
     *                           This is what the arrow renders.
     */
    data class Approach(
        val stored: StoredHazard,
        val rangeM: Double,
        val relativeBearingDeg: Double,
    )

    /**
     * A run of close-together hazards ahead, grouped into ONE thing to announce
     * (D-032) — so a cluster reads as "3 bumps ahead" / "rough section, 40 m"
     * instead of one merged blob or N separate chimes. The grouping is purely a
     * presentation/warning overlay over the individual stored hazards; the rows
     * themselves stay distinct (the store keeps them; this just bundles them for
     * the driver-facing layer).
     *
     * @param lead          the nearest [Approach] in the zone (drives distance +
     *                       arrow + the "warn now?" range gate — a zone is reached
     *                       at its first member).
     * @param count         number of hazards in the zone (≥1).
     * @param lengthM        span from the nearest to the farthest member (m); 0 for
     *                       a singleton. Drives "rough section, N m".
     * @param maxSeverityLevel worst severity across the zone (1..3) — the zone
     *                       chimes/colours at its worst member, not its nearest.
     * @param isRoughSection true when the zone is dominated by ROUGH_SECTION rows
     *                       (sustained washboard) → announce as a stretch, not a count.
     */
    data class HazardZone(
        val lead: Approach,
        val members: List<Approach>,
        val count: Int,
        val lengthM: Double,
        val maxSeverityLevel: Int,
        val isRoughSection: Boolean,
    )

    /**
     * Full ranked list ahead (nearest first) — for an overlay that wants to show
     * "next 3" or to let the warning layer pick by severity, not just distance.
     * Returns empty at crawl / no-fix ([headingReliable] false): without a reliable
     * bearing we can't judge road/direction, so we suppress rather than warn blind.
     */
    fun rank(
        pose: Pose,
        hazards: List<StoredHazard>,
        headingReliable: Boolean,
    ): List<Approach> {
        // R-EXT-4: direction-aware warning is mandatory. Below the heading-reliable
        // floor (crawl / no usable GPS bearing) we CANNOT tell which road or which
        // direction a hazard is on, so we must NOT warn at all rather than warn
        // omnidirectionally (which surfaces opposite-carriageway / cross-street /
        // behind-car hazards — exactly the "wrong road" complaint). At crawl speed the
        // driver can already see the bump, so suppressing costs nothing.
        if (!headingReliable) return emptyList()

        val out = ArrayList<Approach>(hazards.size)
        for (h in hazards) {
            val rangeM = GeoMath.haversineMeters(pose.lat, pose.lng, h.hazard.lat, h.hazard.lng)
            if (rangeM < minRangeM || rangeM > maxRangeM) continue

            // ALTITUDE gate (the over/underpass fix): a hazard at a clearly different
            // level than us is on a different road deck (flyover above, surface below,
            // stacked ramp) and must not warn. Fail-OPEN: if EITHER altitude is unknown,
            // or they're within ALTITUDE_MATCH_M, we keep the hazard — GPS altitude is
            // too noisy (±10–30 m) to suppress on anything but a clear, large delta, so
            // this never introduces a false-negative from noise.
            if (!altitudeMatches(pose.altitudeM, h.hazard.altitudeM)) continue

            val absBearing = GeoMath.bearingDeg(pose.lat, pose.lng, h.hazard.lat, h.hazard.lng)
            val relBearing = signedRelative(pose.bearingDeg.toDouble(), absBearing)

            // Direction-aware gate (R-EXT-4): only keep hazards inside the forward
            // cone (car→hazard bearing within the travel cone). Heading is always
            // reliable here (guarded above).
            if (abs(relBearing) > forwardConeDeg) continue

            // ROAD-MATCH gate (R-EXT-4, "warn only for our road, our direction"): the
            // forward cone keeps anything geometrically AHEAD — including a hazard on
            // the OPPOSITE carriageway, a parallel road, or an over/underpass caught in
            // the cone at a bend. So we ALSO require the hazard's STORED travel heading
            // (the direction the car faced when it was detected) to align with OUR
            // current travel heading within [headingMatchDeg]. We accept ONLY the SAME
            // direction — NOT the 180°-opposite case: per R-EXT-4 the opposite
            // carriageway must not warn. Trade-off: a hazard mapped ONLY in the opposite
            // direction won't warn until it's also observed in our direction.
            // Skip the road-match when the STORED heading is the "unknown" sentinel
            // (<0): it was recorded while the car's GPS bearing was unreliable, so we
            // can't judge road identity from it — fall back to the cone-only result
            // above rather than filtering on a bogus heading (audit accuracy S1).
            if (h.hazard.headingDeg >= 0f) {
                val headingDelta = GeoMath.bearingDeltaDeg(
                    pose.bearingDeg.toDouble(), h.hazard.headingDeg.toDouble()
                ) // 0..180
                if (headingDelta > headingMatchDeg) continue
            }

            out.add(Approach(h, rangeM, relBearing))
        }
        out.sortBy { it.rangeM }
        return out
    }

    /**
     * Group the ranked hazards ahead into [HazardZone]s (D-032). Walking the
     * range-sorted list, any two consecutive hazards closer than
     * [zoneGapM] along the approach are bundled into the same zone; a larger gap
     * starts a new zone. The FIRST zone (the one we're approaching) is what the
     * warning/overlay layer announces.
     *
     * WHY group by along-track gap and not raw count: a "cluster" is a physically
     * contiguous rough patch — three bumps over 15 m is one zone, but a bump now
     * and another 200 m later are two separate warnings. The gap threshold is the
     * physical "is this the same stretch of bad road" question.
     *
     * Returns zones nearest-first. Empty if no hazards qualify.
     */
    fun zonesAhead(
        pose: Pose,
        hazards: List<StoredHazard>,
        headingReliable: Boolean,
    ): List<HazardZone> {
        val rankedByRange = rank(pose, hazards, headingReliable) // nearest range first
        if (rankedByRange.isEmpty()) return emptyList()

        // Walk the list ORDERED BY ALONG-TRACK, not by range (audit detection #2):
        // the gap test `alongTrack(cur) - alongTrack(prev) <= zoneGapM` is only a
        // sane "is this the same stretch" test when the sequence is monotonic in
        // along-track. On a range-sorted list a farther-range but smaller-along-track
        // (lateral) hazard yields a NEGATIVE delta that always merges, while a true
        // along-track-adjacent pair split by a closer lateral hazard wrongly breaks.
        val ranked = rankedByRange.sortedBy { alongTrack(it) }

        val zones = ArrayList<HazardZone>()
        var bucket = ArrayList<Approach>()
        bucket.add(ranked[0])
        for (i in 1 until ranked.size) {
            val prev = ranked[i - 1]
            val cur = ranked[i]
            // Group on ALONG-TRACK separation, not raw car-to-hazard range (audit
            // detection #7). Two hazards at nearly equal range but on opposite sides
            // of the forward cone (one per lane) have ~0 range delta yet are far
            // apart cross-track — grouping on range would wrongly merge them. The
            // along-track delta is the true "same stretch of road" distance. The list
            // is now sorted by along-track so this delta is always non-negative.
            if (alongTrack(cur) - alongTrack(prev) <= zoneGapM) {
                bucket.add(cur)
            } else {
                zones.add(buildZone(bucket))
                bucket = ArrayList()
                bucket.add(cur)
            }
        }
        zones.add(buildZone(bucket))
        // Re-order the zones nearest-first by their lead's range so the FIRST zone is
        // still the one we're physically approaching (the along-track sort above is
        // only for grouping; the announce order is by how soon we reach the zone).
        zones.sortBy { it.lead.rangeM }
        return zones
    }

    /**
     * Signed along-track distance of an [approach] (m): its range projected onto the
     * travel bearing. cos(relativeBearing) folds out the cross-track offset, so two
     * hazards in different lanes at the same range collapse to nearly the same
     * along-track value and a hazard genuinely farther down the road reads farther.
     * (When heading is unreliable rank() returns empty, so this is only ever called
     * with a trustworthy bearing — relativeBearing is always meaningful here.)
     */
    private fun alongTrack(a: Approach): Double =
        a.rangeM * cos(Math.toRadians(a.relativeBearingDeg))

    private fun buildZone(members: List<Approach>): HazardZone {
        // Members are now along-track-sorted (zonesAhead), so the nearest-by-range
        // lead is NOT necessarily members.first(); pick it explicitly. The lead drives
        // the "warn now?" range gate + arrow, so it must be the closest member.
        val lead = members.minByOrNull { it.rangeM } ?: members.first()
        // Length is the along-track span of the zone, not a raw range difference
        // (audit detection #7): a tight cluster spread laterally has ~0 range delta
        // but a real along-track extent. Take max−min of the along-track projection
        // across members so it reflects the actual stretch of road covered. Only
        // members with NON-NEGATIVE along-track count (audit detection #2): when
        // heading is unreliable, hazards behind the car have alongTrack≈−range, and
        // spanning from a behind-car negative to an ahead positive fabricates a huge
        // bogus stretch. Clamp each to ≥0 so a behind-car hazard can't inflate it.
        var minAlong = Double.MAX_VALUE
        var maxAlong = -Double.MAX_VALUE
        for (m in members) {
            val at = alongTrack(m).coerceAtLeast(0.0)
            if (at < minAlong) minAlong = at
            if (at > maxAlong) maxAlong = at
        }
        val lengthM = (maxAlong - minAlong).coerceAtLeast(0.0)
        val maxSev = members.maxOf { it.stored.hazard.severity.level }
        // Rough-section if MOST members are ROUGH_SECTION rows, OR the zone is a
        // dense run of small hazards over a stretch (the washboard signature).
        val roughCount = members.count { it.stored.hazard.type == HazardType.ROUGH_SECTION }
        // Require count>1 so a lone ROUGH_SECTION row doesn't render as "Rough section
        // · 0 m" (a singleton has lengthM=0); a one-off rough row announces as a normal
        // single hazard. A genuine rough STRETCH is always ≥2 grouped rows.
        val isRough = members.size > 1 && roughCount * 2 >= members.size && roughCount > 0
        return HazardZone(
            lead = lead,
            members = members,
            count = members.size,
            lengthM = lengthM,
            maxSeverityLevel = maxSev,
            isRoughSection = isRough,
        )
    }

    /**
     * Signed bearing of [target] relative to [travel], in −180..+180.
     * + = target is to the right of travel, − = to the left, 0 = dead ahead.
     */
    private fun signedRelative(travel: Double, target: Double): Double {
        var d = (target - travel) % 360.0
        if (d > 180.0) d -= 360.0
        if (d < -180.0) d += 360.0
        return d
    }

    companion object {
        /** ±45° forward cone — a hazard must be roughly in our travel direction
         *  to warn. Wide enough to keep hazards through a bend, narrow enough to
         *  exclude the opposite carriageway (R-EXT-4). PROVISIONAL. */
        const val DEFAULT_FORWARD_CONE_DEG = 45.0

        /** Don't surface hazards farther than this — beyond warning interest even
         *  at highway speed (the warn layer picks the actual lead distance). */
        const val DEFAULT_MAX_RANGE_M = 300.0

        /** Under this range it's effectively under the car — too late to warn,
         *  and avoids a 0 m flicker as we pass over it. */
        const val DEFAULT_MIN_RANGE_M = 3.0

        /** Max along-track gap (m) between consecutive hazards for them to belong
         *  to the SAME zone (D-032). 30 m bundles a genuinely contiguous rough
         *  patch (a few bumps over a short stretch) while keeping a bump-now and a
         *  bump-200-m-later as separate warnings. PROVISIONAL — tune on real drives. */
        const val DEFAULT_ZONE_GAP_M = 30.0

        /** Max angle (deg) between our travel heading and the hazard's STORED detection
         *  heading for it to count as "our road, our direction" — SAME-direction only
         *  (the opposite-carriageway branch was removed per R-EXT-4). 35° covers GPS-
         *  bearing noise (~±10–15° above the 2.5 m/s reliable floor) plus real road
         *  curvature between detection and approach, while excluding genuine
         *  intersecting / diverging roads (typically ≥45° off our heading). Was 50°,
         *  too permissive once opposite-direction acceptance was dropped. PROVISIONAL —
         *  widen toward 40° only if curvy mapped roads start false-negativing. */
        const val DEFAULT_HEADING_MATCH_DEG = 35.0
    }
}

package com.overdrive.app.roadsense.store

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Spatial bucketing for RoadSense's "ahead of me" hazard lookups [D-006].
 *
 * Instead of an R-tree (extension dependency, not available in H2 by default
 * and overkill for a few-thousand-row local store), we quantize lat/lng to a
 * fixed grid of ~50 m cells and pack the (latCell, lngCell) pair into a single
 * `Long`. That gives an O(1) bucket key we can index in H2 and use verbatim in
 * `WHERE tile IN (...)` for both the local approach query and the delta-sync
 * tile cursor (R-CRD-5) — same key on both sides of the wire.
 *
 * Pure object: no Android imports, fully unit-testable. The [GeoMath] helpers
 * (Haversine distance + bearing) live alongside it because the ApproachEngine,
 * the store's merge-radius check, and the consensus model all need them.
 *
 * ── Cell-size math ───────────────────────────────────────────────────────────
 * One degree of latitude is ~111_320 m everywhere on Earth, so:
 *
 *     50 m / 111_320 m·deg⁻¹ ≈ 0.000449 deg of latitude per 50 m cell.
 *
 * We round to [CELL_DEG] = 0.00045 (a hair over 50 m, ~50.1 m — close enough
 * that a hazard never straddles more than the current + neighbour cells we
 * already query).
 *
 * Longitude is the awkward axis: a degree of longitude shrinks with latitude
 * (× cos(lat)), so a fixed 0.00045-deg lng cell is ~50 m at the equator but
 * only ~35 m at 45° and ~25 m at 60°. We DELIBERATELY DO NOT correct for that
 * here, and the simplification is safe for two reasons:
 *   1. Cells getting *smaller* toward the poles only makes the grid finer than
 *      50 m — it never makes a cell larger than ~50 m, so a hazard still falls
 *      inside the current-or-neighbour 3×3 block that [neighborTiles] returns.
 *   2. The actual "is this the same hazard / is it within warn range" decisions
 *      are made with true-metric [GeoMath.haversineMeters], never with the cell
 *      grid. The grid is only a coarse pre-filter to keep SQL off a full scan.
 * A cos(lat)-corrected lng cell would buy nothing but a latitude-dependent key
 * that no longer round-trips cleanly to the cloud tile space, so we keep it
 * simple and uniform-in-degrees. Revisit only if hazard density ever demands a
 * tighter pre-filter (D-006: "revisit only if density demands it").
 */
object SpatialIndex {

    /**
     * Grid cell size in degrees. ~50 m of latitude (50 / 111_320 ≈ 0.000449).
     * Used for BOTH axes — see the longitude note in the class doc for why the
     * cos(lat) shrink is accepted rather than corrected.
     */
    const val CELL_DEG = 0.00045

    /**
     * Bit shift used to interleave the lat-cell and lng-cell halves of the key.
     * 32 bits per axis: a signed cell index comfortably covers the whole globe.
     *   lat cells: ±90 / 0.00045  ≈ ±200_000   (well within ±2³¹)
     *   lng cells: ±180 / 0.00045 ≈ ±400_000    (ditto)
     * We bias each cell index by [AXIS_BIAS] before packing so the stored value
     * is always non-negative per half, then mask the low 32 bits.
     */
    private const val AXIS_SHIFT = 32
    private const val AXIS_MASK = 0xFFFFFFFFL

    /**
     * Added to each (possibly negative) cell index before packing so both
     * halves stay in the unsigned 32-bit range. 2³¹ comfortably exceeds the
     * ~400k max cell magnitude above, so there is no wraparound.
     */
    private const val AXIS_BIAS = 0x80000000L // 2^31

    /**
     * Quantize a lat/lng to its ~50 m cell and pack into a single Long key.
     *
     * Stable and reversible-free: callers only ever compare keys for equality
     * / membership, never decode them. The same (lat,lng) always maps to the
     * same key on any device, which is what makes the cloud tile space shared.
     */
    fun tileKey(lat: Double, lng: Double): Long {
        val latCell = Math.floor(lat / CELL_DEG).toLong()
        val lngCell = Math.floor(lng / CELL_DEG).toLong()
        return packCells(latCell, lngCell)
    }

    /**
     * The current tile plus the 8 surrounding cells (a 3×3 block centred on the
     * point) — used for approach queries that may straddle a cell boundary, and
     * for the same-spot merge search in [RoadSenseStore.upsertDetection].
     *
     * Returns the centre cell first; the order of the rest is unspecified.
     */
    fun neighborTiles(lat: Double, lng: Double): LongArray {
        val latCell = Math.floor(lat / CELL_DEG).toLong()
        val lngCell = Math.floor(lng / CELL_DEG).toLong()
        val out = LongArray(9)
        var i = 0
        // Centre first, then the ring — keeps the "my tile" key at index 0 for
        // any caller that wants to special-case it.
        out[i++] = packCells(latCell, lngCell)
        for (dLat in -1..1) {
            for (dLng in -1..1) {
                if (dLat == 0 && dLng == 0) continue
                out[i++] = packCells(latCell + dLat, lngCell + dLng)
            }
        }
        return out
    }

    /** Pack two 32-bit-biased cell indices into one Long (lat in the high half). */
    private fun packCells(latCell: Long, lngCell: Long): Long {
        val latPacked = (latCell + AXIS_BIAS) and AXIS_MASK
        val lngPacked = (lngCell + AXIS_BIAS) and AXIS_MASK
        return (latPacked shl AXIS_SHIFT) or lngPacked
    }
}

/**
 * Pure great-circle geometry helpers. Kept in the same file as [SpatialIndex]
 * because they are the other half of the "where am I relative to this hazard"
 * question and share zero Android dependency — both are trivially unit-testable.
 */
object GeoMath {

    /** Mean Earth radius in metres (WGS-84 mean). */
    private const val EARTH_RADIUS_M = 6_371_000.0

    /**
     * Great-circle distance between two lat/lng points, in metres (Haversine).
     * Accurate to well under a metre at the ranges RoadSense cares about
     * (tens to low hundreds of metres), which is all the merge-radius and
     * approach-distance logic needs.
     */
    fun haversineMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dPhi = Math.toRadians(lat2 - lat1)
        val dLambda = Math.toRadians(lng2 - lng1)
        val sinDPhi = sin(dPhi / 2.0)
        val sinDLambda = sin(dLambda / 2.0)
        val a = sinDPhi * sinDPhi + cos(phi1) * cos(phi2) * sinDLambda * sinDLambda
        val c = 2.0 * atan2(sqrt(a), sqrt(1.0 - a))
        return EARTH_RADIUS_M * c
    }

    /**
     * Initial bearing FROM point 1 TO point 2, in degrees clockwise from true
     * north, normalized to [0, 360). The ApproachEngine compares this against
     * the vehicle's travel bearing (a cone) to keep only hazards roughly ahead.
     */
    fun bearingDeg(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dLambda = Math.toRadians(lng2 - lng1)
        val y = sin(dLambda) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLambda)
        val deg = Math.toDegrees(atan2(y, x))
        return (deg + 360.0) % 360.0
    }

    /**
     * Smallest absolute difference between two bearings, in degrees [0, 180].
     * Handles the 0/360 wrap so a heading of 359° and a hazard bearing of 1°
     * read as 2° apart, not 358°. Convenience for cone filtering by callers.
     */
    fun bearingDeltaDeg(a: Double, b: Double): Double {
        var d = abs(a - b) % 360.0
        if (d > 180.0) d = 360.0 - d
        return d
    }
}

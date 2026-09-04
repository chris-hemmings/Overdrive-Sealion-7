package com.overdrive.app.navmap.nav

/**
 * Plain, immutable data model for the RoadSense navigation map.
 *
 * <p>These are deliberately framework-free (no Android imports) so the
 * routing client, polyline codec and the guidance state machine can be unit
 * tested on the JVM. All coordinates are WGS-84 decimal degrees; all
 * distances are meters; all durations are seconds.
 */

/**
 * A single WGS-84 geographic coordinate.
 *
 * @property lat latitude in decimal degrees (north positive)
 * @property lng longitude in decimal degrees (east positive)
 */
data class GeoPoint(
    val lat: Double,
    val lng: Double
)

/**
 * One turn-by-turn maneuver as returned by Valhalla's `/route` directions.
 *
 * @property instruction human-readable instruction text (e.g. "Turn left onto Main St")
 * @property type the Valhalla maneuver type integer (see Valhalla
 *   `TripDirections::Maneuver::Type`; e.g. 0=none, 1=start, 4=destination,
 *   10/15=turn left, 9/14=turn right, etc.). Kept as a raw int so the
 *   Activity/voice layer can map it to icons/phrasing without this layer
 *   committing to an enum.
 * @property beginShapeIndex index into [NavRoute.points] where this maneuver
 *   begins (the route vertex at which the maneuver's instruction applies)
 * @property lengthMeters length of this maneuver's road segment, in meters
 * @property timeSeconds estimated time to traverse this maneuver, in seconds
 * @property lat latitude (decimal degrees) of the maneuver point, taken from
 *   `points[beginShapeIndex]`
 * @property lng longitude (decimal degrees) of the maneuver point
 * @property roundaboutExitCount for a roundabout maneuver, which exit to take
 *   (Valhalla `roundabout_exit_count`); 0 when not a roundabout / absent.
 * @property bearingBefore heading (deg) approaching the maneuver point, or -1.
 * @property bearingAfter heading (deg) leaving the maneuver point, or -1. Together
 *   with [bearingBefore] these let the UI draw a true turn/exit angle.
 * @property verbalPre concise spoken pre-instruction ("Turn left onto Main Street")
 *   — better for TTS than the on-screen [instruction]; empty if absent.
 * @property verbalPost spoken post-instruction ("Continue for 2 miles"); empty if absent.
 */
data class RouteManeuver(
    val instruction: String,
    val type: Int,
    val beginShapeIndex: Int,
    val lengthMeters: Double,
    val timeSeconds: Double,
    val lat: Double,
    val lng: Double,
    val roundaboutExitCount: Int = 0,
    val bearingBefore: Int = -1,
    val bearingAfter: Int = -1,
    val verbalPre: String = "",
    val verbalPost: String = ""
)

/**
 * A fully decoded route: the polyline geometry plus the maneuver list and
 * trip totals.
 *
 * @property points the decoded full route polyline, in order from origin to
 *   destination. [RouteManeuver.beginShapeIndex] indexes into this list.
 * @property maneuvers ordered turn-by-turn maneuvers
 * @property totalDistanceMeters total trip distance in meters
 * @property totalDurationSeconds total trip duration in seconds
 */
data class NavRoute(
    val points: List<GeoPoint>,
    val maneuvers: List<RouteManeuver>,
    val totalDistanceMeters: Double,
    val totalDurationSeconds: Double
)

/**
 * A single forward-geocoding (search) result.
 *
 * @property label readable display string (e.g. "Eiffel Tower, Paris, France")
 * @property lat latitude in decimal degrees
 * @property lng longitude in decimal degrees
 */
data class SearchResult(
    val label: String,
    val lat: Double,
    val lng: Double
)

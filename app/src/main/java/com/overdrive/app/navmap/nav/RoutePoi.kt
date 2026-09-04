package com.overdrive.app.navmap.nav

/**
 * Plain, immutable data model for a point of interest (POI) shown on the
 * RoadSense navigation map — sourced from free OpenStreetMap data via the
 * public Overpass API (see [OverpassPoiClient]).
 *
 * <p>Framework-free (no Android imports) so it can be unit tested on the JVM,
 * matching the rest of [com.overdrive.app.navmap.nav]. Coordinates are WGS-84
 * decimal degrees.
 *
 * @property kind what category of POI this is ([PoiKind.CHARGING] EV charger
 *   or [PoiKind.FUEL] petrol/diesel station)
 * @property name the OSM `name` tag, or an empty string when the OSM element is
 *   unnamed (many chargers/pumps are) — callers should fall back to a generic
 *   label per [kind] when this is blank
 * @property lat latitude in decimal degrees (north positive)
 * @property lng longitude in decimal degrees (east positive)
 */
data class RoutePoi(
    val kind: PoiKind,
    val name: String,
    val lat: Double,
    val lng: Double
)

/**
 * The categories of POI this client can query.
 *
 * <p>Each maps 1:1 to an OSM `amenity` tag value:
 * - [CHARGING] -> `amenity=charging_station`
 * - [FUEL] -> `amenity=fuel`
 */
enum class PoiKind {
    /** EV charging station (`amenity=charging_station`). */
    CHARGING,

    /** Fuel / petrol / diesel station (`amenity=fuel`). */
    FUEL
}

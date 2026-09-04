package com.overdrive.app.navmap

import android.content.Context
import android.util.Log
import com.overdrive.app.navmap.nav.SearchResult
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists the user's CURRENT trip itinerary (the ordered list of stops after the
 * origin: [stop1, …, destination]) so leaving the map and coming back RESTORES the
 * trip instead of dropping the user back at an empty search box.
 *
 * <p>Why a store and not just savedInstanceState: the user explicitly wants the
 * itinerary to survive *leaving* the Activity (back to the app shell, or even the
 * Activity being destroyed under memory pressure / a daemon restart), not only a
 * config change. So it's a small persisted JSON blob, written whenever the
 * itinerary changes and cleared when the trip is abandoned / arrived.
 *
 * <p>Only the itinerary stops are persisted — NOT the computed polyline. The
 * origin is always the live GPS fix, and the route is recomputed fresh on
 * restore (a stale polyline from an old origin would be wrong anyway). This keeps
 * the blob tiny and always-valid.
 *
 * <p>Mirrors [RecentSearchStore] / [SavedPlacesStore]: one JSON key in a dedicated
 * prefs file, all reads/writes defensive (never throw).
 */
object NavTripStore {

    private const val TAG = "NavTripStore"
    private const val PREFS_NAME = "navmap_active_trip"
    private const val KEY_STOPS = "trip_stops"
    /** Whether the persisted trip was actively navigating (vs just previewed). */
    private const val KEY_NAVIGATING = "trip_navigating"

    private const val KEY_LABEL = "label"
    private const val KEY_LAT = "lat"
    private const val KEY_LNG = "lng"

    /** A restored trip: the ordered itinerary + whether guidance was running. */
    data class Trip(
        val stops: List<SearchResult>,
        val navigating: Boolean
    )

    /**
     * Persist the current itinerary. [stops] is the ordered list AFTER the origin
     * ([stop1, …, destination]); an empty list clears the saved trip. [navigating]
     * records whether turn-by-turn was active so a restore can resume immersive
     * follow vs just re-show the route preview. Never throws.
     */
    fun save(context: Context, stops: List<SearchResult>, navigating: Boolean) {
        try {
            if (stops.isEmpty()) { clear(context); return }
            val arr = JSONArray()
            for (s in stops) {
                if (s.label.isBlank()) continue
                arr.put(JSONObject()
                    .put(KEY_LABEL, s.label)
                    .put(KEY_LAT, s.lat)
                    .put(KEY_LNG, s.lng))
            }
            prefs(context).edit()
                .putString(KEY_STOPS, arr.toString())
                .putBoolean(KEY_NAVIGATING, navigating)
                .apply()
        } catch (e: Throwable) {
            Log.w(TAG, "save failed: ${e.message}")
        }
    }

    /** Load the persisted trip, or null if none / corrupt / empty. Never throws. */
    fun load(context: Context): Trip? {
        return try {
            val raw = prefs(context).getString(KEY_STOPS, null)
            if (raw.isNullOrBlank()) return null
            val arr = JSONArray(raw)
            val stops = ArrayList<SearchResult>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val label = obj.optString(KEY_LABEL, "")
                if (label.isBlank()) continue
                val lat = obj.optDouble(KEY_LAT, Double.NaN)
                val lng = obj.optDouble(KEY_LNG, Double.NaN)
                if (lat.isNaN() || lng.isNaN()) continue
                stops.add(SearchResult(label, lat, lng))
            }
            if (stops.isEmpty()) null
            else Trip(stops, prefs(context).getBoolean(KEY_NAVIGATING, false))
        } catch (e: Throwable) {
            Log.w(TAG, "load failed: ${e.message}")
            null
        }
    }

    /** Clear the saved trip (on arrival / cancel). Never throws. */
    fun clear(context: Context) {
        try {
            prefs(context).edit().remove(KEY_STOPS).remove(KEY_NAVIGATING).apply()
        } catch (e: Throwable) {
            Log.w(TAG, "clear failed: ${e.message}")
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

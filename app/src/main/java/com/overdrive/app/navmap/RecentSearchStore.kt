package com.overdrive.app.navmap

import android.content.Context
import android.util.Log
import com.overdrive.app.navmap.nav.SearchResult
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistent "recent searches" store for the RoadSense map — Gmaps-style.
 *
 * Holds the user's most-recently-chosen navigation destinations so the search
 * field can surface them as a dropdown. The parent ([RoadSenseMapActivity]) wires
 * this in:
 *  - it shows [getAll] in the search dropdown when the field is focused and empty, and
 *  - it calls [add] whenever a destination is actually chosen (route started),
 *    promoting it to the top of the list.
 *
 * Dedupe rule: before prepending, any existing entry that is the "same place" is
 * removed first, so re-picking a destination just bumps it to the front rather
 * than creating a duplicate. Two entries are the same place when their labels
 * match (case-insensitive, trimmed) OR their coordinates are within
 * [COORD_EPSILON] (~0.0001 deg, ≈11 m) on both axes.
 *
 * Cap: the list is trimmed to the [MAX_RECENT] most-recent entries.
 *
 * Storage: a single SharedPreferences key ([KEY_RECENTS]) in a dedicated prefs
 * file ([PREFS_NAME]) holding a JSON array string. Each element is
 * `{"label":..,"lat":..,"lng":..}`. The dedicated file avoids any collision with
 * the shared "overdrive_prefs" used by PreferencesManager. Reads/writes are
 * defensive: parsing never throws, and a corrupt/missing value yields an empty
 * list. SharedPreferences I/O is cheap and synchronous; the parent may safely
 * call these from a background thread.
 */
object RecentSearchStore {

    private const val TAG = "RecentSearchStore"
    private const val PREFS_NAME = "navmap_recent"
    private const val KEY_RECENTS = "recent_searches"

    /** Maximum number of recent destinations retained. */
    const val MAX_RECENT = 8

    /**
     * Coordinate match tolerance, in decimal degrees (~0.0001 ≈ 11 m). Two
     * results within this distance on both lat and lng are treated as the
     * same place for dedupe purposes.
     */
    private const val COORD_EPSILON = 0.0001

    private const val KEY_LABEL = "label"
    private const val KEY_LAT = "lat"
    private const val KEY_LNG = "lng"

    /**
     * Prepend [result] to the recent list (most-recent-first), removing any
     * existing entry for the same place (see dedupe rule in the class KDoc),
     * cap the list at [MAX_RECENT], and persist. Blank labels are ignored.
     * Never throws.
     */
    fun add(context: Context, result: SearchResult) {
        try {
            if (result.label.isBlank()) {
                return
            }
            val current = getAll(context)
            val deduped = current.filterNot { isSamePlace(it, result) }
            val updated = (listOf(result) + deduped).take(MAX_RECENT)
            persist(context, updated)
        } catch (e: Throwable) {
            Log.w(TAG, "add failed: ${e.message}")
        }
    }

    /**
     * Return the recent destinations, most-recent-first. Never throws; a
     * missing or corrupt store yields an empty list.
     */
    fun getAll(context: Context): List<SearchResult> {
        return try {
            val raw = prefs(context).getString(KEY_RECENTS, null)
            if (raw.isNullOrBlank()) {
                emptyList()
            } else {
                parse(raw)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "getAll failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * Remove a single recent entry matching [result] (same-place rule — see the
     * class KDoc / [isSamePlace]) and persist. A no-op if no entry matches.
     * Never throws.
     */
    fun remove(context: Context, result: SearchResult) {
        try {
            val current = getAll(context)
            val updated = current.filterNot { isSamePlace(it, result) }
            if (updated.size != current.size) {
                persist(context, updated)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "remove failed: ${e.message}")
        }
    }

    /** Wipe all recent searches. Never throws. */
    fun clear(context: Context) {
        try {
            prefs(context).edit().remove(KEY_RECENTS).apply()
        } catch (e: Throwable) {
            Log.w(TAG, "clear failed: ${e.message}")
        }
    }

    // ----- internals -----

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Two results are the same place if labels match (case-insensitive, trimmed)
     * OR coords are within [COORD_EPSILON] on both axes. Public so callers that
     * MERGE result sets (e.g. the map's local-saved-places-into-autocomplete
     * surfacing) can dedup against this same rule rather than re-deriving it.
     */
    fun isSamePlace(a: SearchResult, b: SearchResult): Boolean {
        if (a.label.trim().equals(b.label.trim(), ignoreCase = true)) {
            return true
        }
        return Math.abs(a.lat - b.lat) <= COORD_EPSILON &&
            Math.abs(a.lng - b.lng) <= COORD_EPSILON
    }

    /** Defensive JSON parse — skips any malformed element, never throws. */
    private fun parse(raw: String): List<SearchResult> {
        val out = ArrayList<SearchResult>()
        val arr = JSONArray(raw)
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val label = obj.optString(KEY_LABEL, "")
            if (label.isBlank()) {
                continue
            }
            // optDouble returns NaN for a missing/non-numeric value.
            val lat = obj.optDouble(KEY_LAT, Double.NaN)
            val lng = obj.optDouble(KEY_LNG, Double.NaN)
            if (lat.isNaN() || lng.isNaN()) {
                continue
            }
            out.add(SearchResult(label, lat, lng))
        }
        return out
    }

    /** Serialize and write the list as a JSON array string. Never throws. */
    private fun persist(context: Context, items: List<SearchResult>) {
        try {
            val arr = JSONArray()
            for (item in items) {
                val obj = JSONObject()
                obj.put(KEY_LABEL, item.label)
                obj.put(KEY_LAT, item.lat)
                obj.put(KEY_LNG, item.lng)
                arr.put(obj)
            }
            prefs(context).edit().putString(KEY_RECENTS, arr.toString()).apply()
        } catch (e: Throwable) {
            Log.w(TAG, "persist failed: ${e.message}")
        }
    }
}

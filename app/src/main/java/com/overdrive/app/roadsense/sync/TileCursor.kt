package com.overdrive.app.roadsense.sync

import com.overdrive.app.config.UnifiedConfigManager
import org.json.JSONObject

/**
 * Per-tile "last synced" timestamps for delta downloads (R-CRD-5).
 *
 * The free-of-cost guarantee (G-6) hinges on never re-downloading data we already
 * have. For each map tile we remember the newest `updated_ms` we've pulled; the
 * next download asks the server only for rows newer than that. A device driving
 * its usual commute therefore pulls almost nothing after the first sync of each
 * tile — exactly the property that keeps us inside Cloudflare's free tier.
 *
 * Persisted in the `roadSense` UCM section under `tileCursors` (a tile→ms map) so
 * cursors survive daemon restarts. Kept small: we prune tiles not touched in a
 * long time (a cursor for a tile you'll never revisit is dead weight).
 *
 * Thread-safety: although the sync tick is the primary caller, [clear] is invoked
 * from the daemon HTTP handler thread (RoadSenseController.deleteCloudUploads via
 * RoadSenseApiHandler.handleDeleteCloud) — a DIFFERENT thread from the tick that
 * runs [sinceMap]/[advance] (audit concurrency). Concurrent structural mutation of
 * a plain HashMap can throw or corrupt its table, so every public method guards the
 * map with [lock]. The mutations are tiny; this never touches the 100 Hz hot path.
 */
class TileCursor {

    private val lock = Any()
    private val cursors = HashMap<Long, Long>()
    private var loaded = false

    /** Lazy-load from UCM on first use (cross-UID: daemon reads what it wrote). */
    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        try {
            val section = UnifiedConfigManager.forceReload().optJSONObject(SECTION) ?: return
            val obj = section.optJSONObject(KEY) ?: return
            val it = obj.keys()
            while (it.hasNext()) {
                val k = it.next()
                val tile = k.toLongOrNull() ?: continue
                cursors[tile] = obj.optLong(k, 0L)
            }
        } catch (_: Throwable) { /* missing/corrupt cursors → start fresh */ }
    }

    /** Snapshot of cursors for the requested tiles, for a download call. */
    fun sinceMap(tiles: List<Long>): Map<Long, Long> = synchronized(lock) {
        ensureLoaded()
        tiles.associateWith { cursors[it] ?: 0L }
    }

    /**
     * Advance cursors after a successful download (only ever moves forward).
     *
     * Each tile advances to ITS OWN observed high-water — NEVER to another tile's
     * (audit network #9). The earlier design advanced every requested tile to the
     * batch-wide max updated_ms, which silently lost updates: a quiet tile bumped to
     * a busy neighbour's max would then never return a row whose updated_ms landed
     * between the quiet tile's true high-water and that max (the server filters
     * `updated_ms > since` strictly), skipping it forever.
     *
     * Two inputs:
     *  - [requestedFloors]: the per-tile `since` the server was actually given for
     *    each requested tile. A tile that returned NO rows is genuinely caught up to
     *    its OWN query floor only (not to any other tile's data), so we advance it
     *    there. This still kills the re-pull-forever cost (G-6) because each tile's
     *    floor is its own last cursor, not a global minimum.
     *  - [tileHighWater]: tiles that DID return rows advance to the newest row
     *    actually seen FOR THAT TILE.
     */
    fun advance(requestedFloors: Map<Long, Long>, tileHighWater: Map<Long, Long>) = synchronized(lock) {
        ensureLoaded()
        var changed = false
        for ((tile, floor) in requestedFloors) {
            val cur = cursors[tile] ?: 0L
            if (floor > cur) { cursors[tile] = floor; changed = true }
        }
        for ((tile, hw) in tileHighWater) {
            val cur = cursors[tile] ?: 0L
            if (hw > cur) { cursors[tile] = hw; changed = true }
        }
        if (changed) persist()
    }

    /** Persist the cursor map back to UCM. Coalesce calls — it's a full rewrite. */
    private fun persist() {
        // Prune: cap the map so an endlessly-roaming device doesn't grow it without
        // bound. Keep the most-recent MAX_TILES by cursor value (newest synced).
        if (cursors.size > MAX_TILES) {
            val keep = cursors.entries.sortedByDescending { it.value }.take(MAX_TILES)
            cursors.clear()
            keep.forEach { cursors[it.key] = it.value }
        }
        try {
            val obj = JSONObject()
            for ((tile, ms) in cursors) obj.put(tile.toString(), ms)
            UnifiedConfigManager.updateSection(SECTION, JSONObject().put(KEY, obj))
        } catch (_: Throwable) { /* best-effort; a lost cursor just re-pulls one tile */ }
    }

    /** Reset on delete-local (R-SET-5) so a re-sync repopulates from scratch.
     *  Called from the HTTP handler thread, NOT the sync tick — hence the lock. */
    fun clear() = synchronized(lock) {
        ensureLoaded()
        cursors.clear()
        persist()
    }

    companion object {
        private const val SECTION = "roadSense"
        private const val KEY = "tileCursors"
        /** Cap the persisted cursor map (~50 m tiles; 4000 ≈ a large metro's worth). */
        private const val MAX_TILES = 4000
    }
}

package com.overdrive.app.roadsense.sync

import com.overdrive.app.roadsense.detect.RoadSenseHazard
import com.overdrive.app.roadsense.detect.StoredHazard

/**
 * The crowdsource transport seam (D-002). All cloud I/O goes through this so the
 * concrete backend (Cloudflare Workers + D1, D-009) can change without touching
 * detection/store/controller. There's exactly one impl today
 * ([CloudflareEdgeSyncProvider]); a no-op/replay impl is trivial for tests.
 *
 * Two independent directions, mirroring the two independent user toggles (R-SET-2,
 * R-CRD-6): [download] (pull confirmed hazards near a route) and [upload] (push
 * this device's high-confidence local hazards). Both are opt-in, default OFF —
 * the controller only calls these when the respective config flag is on.
 *
 * All methods are blocking network calls — the caller (a daemon sync tick) runs
 * them off the detection/IMU path. Implementations must never throw to the caller;
 * they return a result object so a dead network / bad URL degrades gracefully
 * (offline-first, R-EXT-7) rather than crashing the daemon.
 */
interface RoadSenseSyncProvider {

    /** Result of a delta download for a set of tiles. */
    data class DownloadResult(
        val ok: Boolean,
        /** Confirmed hazards pulled (already filtered to status=confirmed server-side). */
        val hazards: List<RoadSenseHazard>,
        /** Per-tile newest updated_ms seen, so the caller can advance its cursor. */
        val tileHighWater: Map<Long, Long>,
        val error: String? = null,
        /** Server hit its per-response row cap and more rows match — the caller
         *  should re-sync SOON (not wait the full 2.5 h cadence) to drain the rest.
         *  Only set on a cold/dense first sync; steady state is always false. */
        val more: Boolean = false,
    )

    data class UploadResult(
        val ok: Boolean,
        val accepted: Int,
        val error: String? = null,
    )

    /**
     * Pull confirmed hazards in [tiles] changed since each tile's [sinceByTile]
     * cursor (delta-by-tile, R-CRD-5 — never a global dump). Region/route-scoped:
     * the caller passes only the tiles on/ahead of the current route.
     */
    fun download(tiles: List<Long>, sinceByTile: Map<Long, Long>): DownloadResult

    /**
     * Push this device's high-confidence local hazards (D-016 gate applied by the
     * caller). [deviceId] is the rotating anonymous id (R-CRD-7); the server uses
     * it only for consensus distinct-device counting, never to reconstruct tracks.
     */
    fun upload(hazards: List<StoredHazard>, deviceId: String): UploadResult

    /** Delete this device's uploaded rows from the backend (R-SET-5 delete-cloud). */
    fun deleteOwnUploads(deviceId: String): Boolean
}

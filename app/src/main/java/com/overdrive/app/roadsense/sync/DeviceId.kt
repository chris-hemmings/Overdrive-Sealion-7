package com.overdrive.app.roadsense.sync

import com.overdrive.app.config.UnifiedConfigManager
import org.json.JSONObject
import java.util.UUID

/**
 * Rotating anonymous device identity for crowdsource uploads (R-CRD-7, D-009).
 *
 * The id is ONLY used server-side for consensus distinct-device counting — never
 * to reconstruct a track or identify a person. It is:
 *   - cryptographically random (UUID v4),
 *   - stored in the `roadSense` UCM section (`deviceId`), NOT Android Keystore
 *     (that's for crypto keys) and NOT EncryptedSharedPreferences (per-UID, splits
 *     the daemon vs app — see feedback_credentials_unified_pattern). UCM is
 *     file-backed so daemon + app see the same id,
 *   - **rotated** periodically (every [ROTATE_DAYS]) so uploads can't be linked
 *     across long spans into a movement profile. Rotation is privacy hygiene; it
 *     does NOT hurt consensus because confirmation happens within R/T (days), far
 *     shorter than the rotation window.
 *
 * Daemon-side use. First call mints + persists; later calls reuse until rotation.
 */
object DeviceId {

    private const val SECTION = "roadSense"
    private const val KEY_ID = "deviceId"
    private const val KEY_MINTED = "deviceIdMintedMs"
    private const val ROTATE_DAYS = 30L
    private const val ROTATE_MS = ROTATE_DAYS * 24L * 60L * 60L * 1000L

    /**
     * Current anonymous id, minting or rotating as needed. [nowMs] injected for
     * testability. Returns a stable id within a rotation window.
     */
    fun current(nowMs: Long): String {
        val section = try {
            UnifiedConfigManager.forceReload().optJSONObject(SECTION)
        } catch (_: Throwable) { null }
        val existing = section?.optString(KEY_ID, "")?.ifEmpty { null }
        val minted = section?.optLong(KEY_MINTED, 0L) ?: 0L

        if (existing != null && (nowMs - minted) < ROTATE_MS) {
            return existing
        }
        // Mint (first use) or rotate (window elapsed).
        val fresh = UUID.randomUUID().toString()
        try {
            UnifiedConfigManager.updateSection(
                SECTION,
                JSONObject().put(KEY_ID, fresh).put(KEY_MINTED, nowMs),
            )
        } catch (_: Throwable) { /* if persist fails we'll just mint again next time */ }
        return fresh
    }
}

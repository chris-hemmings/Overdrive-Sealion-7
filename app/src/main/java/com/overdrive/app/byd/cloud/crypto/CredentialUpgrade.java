package com.overdrive.app.byd.cloud.crypto;

import com.overdrive.app.config.UnifiedConfigManager;

import org.json.JSONObject;

/**
 * Shared one-shot upgrade of a stored credential field from the LEGACY
 * firmware-fingerprint-bound key to the STABLE device-id-only key, so a future
 * OTA can't strand it (see {@link CredentialCipher}). Used by the Telegram bot
 * token, the BYD-cloud password, and the NavMap routing key.
 *
 * <p>Two correctness guards, both learned from the Telegram-token fix:
 * <ul>
 *   <li><b>Single-key delta:</b> writes ONLY the upgraded key via
 *       {@link UnifiedConfigManager#updateSection}, never the whole
 *       (read-earlier, now-stale) section. updateSection merges per-key under
 *       its cross-process file lock, so a concurrent clear/rotate of OTHER keys
 *       in the same section is preserved (no resurrect of a just-cleared
 *       username/enabled flag).</li>
 *   <li><b>Compare-and-set:</b> re-reads the field FRESH (cross-UID
 *       forceReload) immediately before writing and bails if it changed since
 *       the upgrade was computed — so a concurrent clear/rotate of the SAME
 *       field isn't resurrected.</li>
 * </ul>
 *
 * <p>Best-effort and idempotent: a no-op once the field is stable (or absent,
 * or unrecoverable), never throws into the caller.
 *
 * @return true iff an upgrade write actually committed.
 */
public final class CredentialUpgrade {

    private CredentialUpgrade() {}

    public static boolean reEncryptKeyIfLegacy(String section, String key) {
        try {
            JSONObject sec = UnifiedConfigManager.loadConfig().optJSONObject(section);
            if (sec == null) return false;
            String stored = sec.optString(key, "");
            String upgraded = CredentialCipher.upgradeToStableOrNull(stored);
            if (upgraded == null) return false;  // not legacy / unrecoverable / fail-open guard

            // CAS: re-read fresh and bail if the field changed under us (a
            // concurrent clear/rotate). Narrows the same-key resurrect window;
            // the single-key delta below already prevents cross-key clobber.
            try {
                UnifiedConfigManager.forceReload();
            } catch (Exception ignored) {}
            JSONObject nowSec = UnifiedConfigManager.loadConfig().optJSONObject(section);
            String nowStored = (nowSec != null) ? nowSec.optString(key, "") : "";
            if (!stored.equals(nowStored)) return false;  // changed → don't resurrect

            JSONObject delta = new JSONObject();
            delta.put(key, upgraded);
            return UnifiedConfigManager.updateSection(section, delta);
        } catch (Exception e) {
            return false;
        }
    }
}

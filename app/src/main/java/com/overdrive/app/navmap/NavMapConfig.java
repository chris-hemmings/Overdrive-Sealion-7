package com.overdrive.app.navmap;

import com.overdrive.app.byd.cloud.crypto.CredentialCipher;
import com.overdrive.app.byd.cloud.crypto.CredentialUpgrade;
import com.overdrive.app.config.UnifiedConfigManager;
import com.overdrive.app.logging.DaemonLogger;

import org.json.JSONObject;

/**
 * RoadSense Map configuration — the routing (BYOK) credential + endpoint.
 *
 * <p>Mirrors {@link com.overdrive.app.byd.cloud.BydCloudConfig} exactly: reads/writes
 * the {@code navMap} section of {@link UnifiedConfigManager}, and protects the secret
 * routing API key at rest with {@link CredentialCipher} (the SAME AES/GCM, device-ID-derived
 * scheme used for BYD Cloud's rawPassword — {@code ENC:} prefix, transparent legacy-plaintext
 * migration on first read). The map basemap (OpenFreeMap) needs no key; ONLY the routing
 * provider (cloud Valhalla) is BYOK, so the key is the one secret here.
 *
 * <p>Design intent: we NEVER ship our own routing key (that would violate provider free-tier
 * ToS and risk a fleet-wide ban). The user pastes their own personal key + endpoint; usage is
 * sparse (~1 route calc/trip) so it stays inside any free tier. See dev/roadsense-map/00-DESIGN.md.
 */
public final class NavMapConfig {

    private static final DaemonLogger logger = DaemonLogger.getInstance("NavMapConfig");

    private static final String SECTION = "navMap";

    /**
     * Pre-filled so the user only pastes a key. Defaults to Stadia Maps' Valhalla
     * route endpoint (matching the in-app "Get a free key" signup guide); the key
     * is appended as ?api_key= by ValhallaRouteClient. User-overridable for any
     * other Valhalla-compatible provider.
     */
    public static final String DEFAULT_ROUTING_ENDPOINT =
            "https://api.stadiamaps.com/route/v1";

    public final boolean enabled;
    /** Routing provider base endpoint (Valhalla-compatible). Never secret. */
    public final String routingEndpoint;
    /** BYOK routing API key — secret, stored encrypted, returned here in plaintext. */
    public final String routingApiKey;

    private NavMapConfig(boolean enabled, String routingEndpoint, String routingApiKey) {
        this.enabled = enabled;
        this.routingEndpoint = (routingEndpoint != null && !routingEndpoint.trim().isEmpty())
                ? routingEndpoint.trim()
                : DEFAULT_ROUTING_ENDPOINT;
        this.routingApiKey = routingApiKey != null ? routingApiKey : "";
    }

    /**
     * Load config from UnifiedConfigManager. Handles legacy plaintext keys transparently
     * (decrypts {@code ENC:} values; migrates a legacy plaintext key to protected form on
     * first read), exactly like {@link com.overdrive.app.byd.cloud.BydCloudConfig#fromUnifiedConfig}.
     */
    public static NavMapConfig fromUnifiedConfig() {
        JSONObject config = UnifiedConfigManager.loadConfig();
        JSONObject navMap = config.optJSONObject(SECTION);
        if (navMap == null) {
            return new NavMapConfig(false, DEFAULT_ROUTING_ENDPOINT, "");
        }

        String storedKey = navMap.optString("routingApiKey", "");
        String routingApiKey = CredentialCipher.decrypt(storedKey);

        // Migrate legacy plaintext to protected form on first read (best-effort).
        if (!storedKey.isEmpty() && !CredentialCipher.isEncrypted(storedKey)) {
            migrateRoutingKey(navMap, routingApiKey);
        } else {
            // Upgrade a legacy firmware-fingerprint-bound ciphertext to the
            // stable key so an OTA can't strand it (same fix as the Telegram
            // token + BYD-cloud password). Single-key-delta + CAS so a
            // concurrent clearRouting()/saveRouting() isn't resurrected.
            CredentialUpgrade.reEncryptKeyIfLegacy(SECTION, "routingApiKey");
        }

        return new NavMapConfig(
                navMap.optBoolean("enabled", false),
                navMap.optString("routingEndpoint", DEFAULT_ROUTING_ENDPOINT),
                routingApiKey
        );
    }

    /** Migrate a legacy plaintext routing key to protected form. */
    private static void migrateRoutingKey(JSONObject navMap, String plainKey) {
        try {
            String encrypted = CredentialCipher.encrypt(plainKey);
            if (!CredentialCipher.isEncrypted(encrypted)) return;  // fail-open guard: never write plaintext back
            // Single-key delta (not the whole stale section) so a concurrent
            // clearRouting()/saveRouting() on other navMap keys isn't resurrected.
            JSONObject delta = new JSONObject();
            delta.put("routingApiKey", encrypted);
            UnifiedConfigManager.updateSection(SECTION, delta);
        } catch (Exception e) {
            // Best-effort — plaintext still works, will migrate on next save.
        }
    }

    /** A routing key is configured (BYOK provided). The basemap works without one. */
    public boolean isRoutingConfigured() {
        return enabled && !routingApiKey.isEmpty() && !routingEndpoint.isEmpty();
    }

    /**
     * Save routing config to UnifiedConfigManager. The key is encrypted before storage with
     * the same CredentialCipher path BYD Cloud uses for rawPassword.
     */
    public static void saveRouting(String routingEndpoint, String routingApiKey) {
        JSONObject navMap = currentSection();
        try {
            navMap.put("enabled", true);
            navMap.put("routingEndpoint",
                    (routingEndpoint != null && !routingEndpoint.trim().isEmpty())
                            ? routingEndpoint.trim()
                            : DEFAULT_ROUTING_ENDPOINT);
            String encKey = CredentialCipher.encrypt(routingApiKey);
            // Never persist a non-empty key in cleartext (encrypt() is fail-open
            // on a JCE error). Empty key legitimately stays "". On failure skip
            // the write rather than write a bare key to the 0666 store.
            if (routingApiKey != null && !routingApiKey.isEmpty()
                    && !CredentialCipher.isEncrypted(encKey)) {
                logger.warn("Credential encryption failed; aborting navMap save (not persisting plaintext)");
                return;
            }
            navMap.put("routingApiKey", encKey);
        } catch (Exception e) {
            throw new RuntimeException("Failed to build navMap config JSON", e);
        }
        UnifiedConfigManager.updateSection(SECTION, navMap);
        logger.info("Saved navMap routing config (endpoint set, key encrypted)");
    }

    /** Clear the stored routing credential. */
    public static void clearRouting() {
        JSONObject navMap = currentSection();
        try {
            navMap.put("enabled", false);
            navMap.put("routingApiKey", "");
        } catch (Exception ignored) {}
        UnifiedConfigManager.updateSection(SECTION, navMap);
    }

    /** Load the current section as a mutable object so a partial save preserves other keys. */
    private static JSONObject currentSection() {
        JSONObject existing = UnifiedConfigManager.loadConfig().optJSONObject(SECTION);
        return existing != null ? existing : new JSONObject();
    }
}

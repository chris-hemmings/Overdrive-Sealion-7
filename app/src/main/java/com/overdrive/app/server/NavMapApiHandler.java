package com.overdrive.app.server;

import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.navmap.NavMapConfig;

import org.json.JSONObject;

import java.io.OutputStream;

/**
 * RoadSense Map routing (BYOK) HTTP API — backs the "Routing" config card on the
 * road-sense.html settings page. Runs in the daemon process (UID 2000) where
 * {@link com.overdrive.app.config.UnifiedConfigManager} and
 * {@link com.overdrive.app.byd.cloud.crypto.CredentialCipher} live, so it calls
 * {@link NavMapConfig} directly (no IPC hop). Mirrors {@link BydCloudApiHandler}'s
 * structure — the secret (routing API key) is stored encrypted and is NEVER returned
 * to the UI; status only exposes a {@code hasKey} boolean (exactly how byd-cloud status
 * never returns the password, only {@code hasLoginKey}).
 *
 * <p>Note: the WebView in this app drops XHR POST bodies, so the web side MUST use
 * {@code fetch()} for the setup/clear POSTs — the handler simply reads {@code body}.
 *
 * Endpoints:
 *  - GET  /api/navmap/routing/status → {success, configured, enabled, endpoint, hasKey}
 *  - POST /api/navmap/routing/setup  → {endpoint, apiKey} → NavMapConfig.saveRouting(...)
 *  - POST /api/navmap/routing/clear  → NavMapConfig.clearRouting()
 */
public class NavMapApiHandler {

    private static final String TAG = "NavMapApiHandler";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    /**
     * Handle RoadSense Map API requests.
     * @return true if handled
     */
    public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        String cleanPath = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;

        if (cleanPath.equals("/api/navmap/routing/status") && method.equals("GET")) {
            handleStatus(out);
            return true;
        }
        if (cleanPath.equals("/api/navmap/routing/setup") && method.equals("POST")) {
            handleSetup(out, body);
            return true;
        }
        if (cleanPath.equals("/api/navmap/routing/clear") && method.equals("POST")) {
            handleClear(out);
            return true;
        }
        // Cluster projection: start / stop / status for projecting the map onto
        // the driver-cluster display (a SUSTAINED holder of ClusterProjectionController).
        if (cleanPath.equals("/api/navmap/cluster/start") && method.equals("POST")) {
            com.overdrive.app.navmap.ClusterMapProjector.start();
            sendClusterStatus(out);
            return true;
        }
        if (cleanPath.equals("/api/navmap/cluster/stop") && method.equals("POST")) {
            com.overdrive.app.navmap.ClusterMapProjector.stop();
            sendClusterStatus(out);
            return true;
        }
        if (cleanPath.equals("/api/navmap/cluster/status") && method.equals("GET")) {
            sendClusterStatus(out);
            return true;
        }
        return false;
    }

    /** Current cluster-projection state. */
    private static void sendClusterStatus(OutputStream out) throws Exception {
        JSONObject response = new JSONObject();
        try {
            response.put("success", true);
            response.put("projecting", com.overdrive.app.navmap.ClusterMapProjector.isActive());
        } catch (Exception e) {
            response.put("success", false);
            response.put("error", e.getMessage());
        }
        HttpResponse.sendJson(out, response.toString());
    }

    /**
     * GET /api/navmap/routing/status — current routing config state. NEVER returns the
     * actual key — only {@code hasKey} (mirrors byd-cloud status never returning the password).
     */
    private static void handleStatus(OutputStream out) throws Exception {
        JSONObject response = new JSONObject();
        try {
            NavMapConfig config = NavMapConfig.fromUnifiedConfig();
            response.put("success", true);
            response.put("configured", config.isRoutingConfigured());
            response.put("enabled", config.enabled);
            response.put("endpoint", config.routingEndpoint);
            // Never return the key itself — only whether one is set.
            response.put("hasKey", !config.routingApiKey.isEmpty());
        } catch (Exception e) {
            logger.warn(TAG + ": status failed: " + e.getMessage());
            response.put("success", false);
            response.put("error", e.getMessage());
        }
        HttpResponse.sendJson(out, response.toString());
    }

    /**
     * POST /api/navmap/routing/setup — save the BYOK routing endpoint + key.
     *
     * Request body:
     * {
     *   "endpoint": "https://valhalla1.openstreetmap.de/route",  // optional, defaults
     *   "apiKey":   "the-secret-key"                             // required (encrypted at rest)
     * }
     */
    private static void handleSetup(OutputStream out, String body) throws Exception {
        JSONObject response = new JSONObject();
        try {
            JSONObject req = new JSONObject(body == null ? "" : body);
            String endpoint = req.optString("endpoint", "").trim();
            String apiKey = req.optString("apiKey", "").trim();

            if (apiKey.isEmpty()) {
                response.put("success", false);
                response.put("error", "Routing API key is required");
                HttpResponse.sendJson(out, response.toString());
                return;
            }

            // Empty/blank endpoint falls back to the default inside saveRouting.
            NavMapConfig.saveRouting(endpoint, apiKey);
            logger.info(TAG + ": saved routing config (endpoint set, key encrypted)");

            response.put("success", true);
        } catch (Exception e) {
            logger.warn(TAG + ": setup failed: " + e.getMessage());
            response.put("success", false);
            response.put("error", e.getMessage());
        }
        HttpResponse.sendJson(out, response.toString());
    }

    /**
     * POST /api/navmap/routing/clear — clear the stored routing credential.
     */
    private static void handleClear(OutputStream out) throws Exception {
        JSONObject response = new JSONObject();
        try {
            NavMapConfig.clearRouting();
            logger.info(TAG + ": cleared routing config");
            response.put("success", true);
        } catch (Exception e) {
            logger.warn(TAG + ": clear failed: " + e.getMessage());
            response.put("success", false);
            response.put("error", e.getMessage());
        }
        HttpResponse.sendJson(out, response.toString());
    }
}

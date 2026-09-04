package com.overdrive.app.server;

import android.content.Context;

import com.overdrive.app.byd.HazardLightProbe;
import com.overdrive.app.daemon.CameraDaemon;
import com.overdrive.app.daemon.DaemonBootstrap;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Debug endpoint for probing BYD hazard / turn-signal / fog actuation from the
 * daemon (uid 2000). Lets you test the {@link HazardLightProbe} candidates
 * over HTTP without launching a separate {@code app_process}.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET /api/debug/light/resolve} — read-only: which feature IDs
 *       resolved (named SDK const vs CANFD fallback int) + the candidate list.
 *       Safe, no writes.</li>
 *   <li>{@code GET /api/debug/light/fire?candidate=A|B|C&hold=4&confirm=YES} —
 *       fire one candidate (ON → hold seconds → OFF), returns each set()'s SDK
 *       result. Gated by {@code confirm=YES}.</li>
 *   <li>{@code GET /api/debug/light/fire-all?hold=3&confirm=YES} — fire A, B,
 *       then C in sequence. Gated by {@code confirm=YES}.</li>
 * </ul>
 *
 * <p>Write surface, but light-only and self-resetting (OFF always issued).
 * Returns 503 if the daemon Context isn't available (run inside cam_daemon).
 */
public final class LightDebugApiHandler {

    private static final String TAG = "LightDebug";
    private static final long DEFAULT_HOLD_MS = 4000L;
    private static final long MAX_HOLD_MS = 15000L;

    private LightDebugApiHandler() {}

    /**
     * Resolve the daemon's BYD-capable Context. Prefer {@link CameraDaemon#getAppContext()}
     * (the shared context that successfully initialised the BYD device singletons),
     * fall back to {@link DaemonBootstrap#getContext()}.
     */
    private static Context resolveContext() {
        Context ctx = null;
        try { ctx = CameraDaemon.getAppContext(); } catch (Throwable ignore) {}
        if (ctx == null) {
            try { ctx = DaemonBootstrap.getContext(); } catch (Throwable ignore) {}
        }
        return ctx;
    }

    public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        if (!"GET".equals(method)) {
            HttpResponse.sendError(out, 405, "Method Not Allowed");
            return true;
        }

        String pathOnly = path;
        Map<String, String> q = new LinkedHashMap<>();
        int qIdx = path.indexOf('?');
        if (qIdx >= 0) {
            pathOnly = path.substring(0, qIdx);
            for (String pair : path.substring(qIdx + 1).split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0) q.put(pair.substring(0, eq), pair.substring(eq + 1));
            }
        }

        if (pathOnly.equals("/api/debug/light/resolve")) {
            return handleResolve(out);
        }
        if (pathOnly.equals("/api/debug/light/fire")) {
            return handleFire(out, q);
        }
        if (pathOnly.equals("/api/debug/light/fire-all")) {
            return handleFireAll(out, q);
        }

        HttpResponse.sendError(out, 404, "Unknown light debug endpoint");
        return true;
    }

    /** Read-only: show resolved feature IDs + candidate list. No writes. */
    private static boolean handleResolve(OutputStream out) throws Exception {
        JSONObject r = new JSONObject();
        r.put("uid", android.os.Process.myUid());
        r.put("contextAvailable", resolveContext() != null);
        r.put("resolvedFeatureIds", HazardLightProbe.resolvedIds());
        JSONArray cands = new JSONArray();
        for (HazardLightProbe.Candidate c : HazardLightProbe.candidates()) {
            JSONObject o = new JSONObject();
            o.put("id", c.id);
            o.put("label", c.label);
            o.put("deviceClass", c.deviceClass);
            JSONArray fids = new JSONArray();
            for (int f : c.featureIds) fids.put(f);
            o.put("featureIds", fids);
            cands.put(o);
        }
        r.put("candidates", cands);
        r.put("note", "GET /api/debug/light/fire?candidate=A&hold=4&confirm=YES to actuate");
        HttpResponse.sendJson(out, r.toString());
        return true;
    }

    /** Fire one candidate (confirm=YES required). */
    private static boolean handleFire(OutputStream out, Map<String, String> q) throws Exception {
        if (!"YES".equals(q.get("confirm"))) {
            HttpResponse.sendJsonError(out, "Refusing to actuate lights without confirm=YES query param");
            return true;
        }
        Context ctx = resolveContext();
        if (ctx == null) {
            HttpResponse.sendJson(out, 503,
                new JSONObject().put("error", "Daemon Context unavailable — run inside cam_daemon").toString());
            return true;
        }
        String id = q.getOrDefault("candidate", "A");
        HazardLightProbe.Candidate c = HazardLightProbe.candidateById(id);
        if (c == null) {
            HttpResponse.sendJsonError(out, "Unknown candidate '" + id + "' (expected A, B, or C)");
            return true;
        }
        long hold = parseHold(q);
        log("fire candidate=" + c.id + " (" + c.label + ") hold=" + hold + "ms");
        JSONObject result = HazardLightProbe.runCandidate(ctx, c, hold);
        log("fire candidate=" + c.id + " result=" + result);
        HttpResponse.sendJson(out, result.toString());
        return true;
    }

    /** Fire A, B, C in sequence (confirm=YES required). */
    private static boolean handleFireAll(OutputStream out, Map<String, String> q) throws Exception {
        if (!"YES".equals(q.get("confirm"))) {
            HttpResponse.sendJsonError(out, "Refusing to actuate lights without confirm=YES query param");
            return true;
        }
        Context ctx = resolveContext();
        if (ctx == null) {
            HttpResponse.sendJson(out, 503,
                new JSONObject().put("error", "Daemon Context unavailable — run inside cam_daemon").toString());
            return true;
        }
        long hold = parseHold(q);
        JSONArray results = new JSONArray();
        for (HazardLightProbe.Candidate c : HazardLightProbe.candidates()) {
            log("fire-all candidate=" + c.id + " hold=" + hold + "ms");
            results.put(HazardLightProbe.runCandidate(ctx, c, hold));
        }
        JSONObject r = new JSONObject();
        r.put("uid", android.os.Process.myUid());
        r.put("results", results);
        HttpResponse.sendJson(out, r.toString());
        return true;
    }

    private static long parseHold(Map<String, String> q) {
        String s = q.get("hold");
        if (s == null) return DEFAULT_HOLD_MS;
        try {
            long ms = Long.parseLong(s.trim()) * 1000L;
            if (ms < 0) return DEFAULT_HOLD_MS;
            return Math.min(ms, MAX_HOLD_MS);
        } catch (NumberFormatException e) {
            return DEFAULT_HOLD_MS;
        }
    }

    private static void log(String s) {
        try { CameraDaemon.log(TAG + ": " + s); } catch (Throwable ignore) {}
    }
}

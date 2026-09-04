package com.overdrive.app.server;

import com.overdrive.app.auth.AuthManager;
import com.overdrive.app.daemon.CameraDaemon;

import org.json.JSONObject;

import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HTTP API handler for authentication endpoints.
 *
 * Endpoints:
 * - GET  /auth/status     - Check auth status (auth required — surfaces deviceId)
 * - POST /auth/token      - Validate device token and get JWT (rate-limited)
 * - POST /auth/logout     - Clear session
 *
 * Rate limiting on /auth/token: 10 attempts per minute per client identity, then
 * 30s lockout. Identity is the X-Forwarded-For value when present (so a tunnel
 * attacker can't share the loopback bucket with the legitimate WebView), falling
 * back to socket address otherwise.
 */
public class AuthApiHandler {

    // Rate-limit constants — small numbers picked for human convenience while
    // making brute-force attempts on a 64-bit token costly.
    private static final int RATE_LIMIT_WINDOW_MS = 60_000;     // 1 minute window
    private static final int RATE_LIMIT_MAX_ATTEMPTS = 10;      // 10 attempts allowed
    private static final long RATE_LIMIT_LOCKOUT_MS = 30_000;   // 30s lockout after exceeded

    private static final ConcurrentHashMap<String, RateLimitBucket> rateLimits = new ConcurrentHashMap<>();

    // Inline GC counter for the rateLimits map. Without this, a publicly
    // tunneled instance accumulates a permanent entry per scanner IP that
    // ever hits /auth/token (typical: 10²–10⁴ unique IPs/week per public
    // endpoint). pruneStaleRateLimits walks the map opportunistically every
    // RATE_LIMIT_GC_EVERY checkRateLimit calls to keep it bounded.
    private static final java.util.concurrent.atomic.AtomicInteger rateLimitOps =
        new java.util.concurrent.atomic.AtomicInteger(0);
    private static final int RATE_LIMIT_GC_EVERY = 64;
    // Stop bucket-keep growth at a hard cap so a burst of fresh IPs faster
    // than the GC cadence can't push the map above this. Far above the
    // legitimate per-tunnel concurrent client count (hundreds) but well
    // below "leak" territory.
    private static final int RATE_LIMIT_MAX_BUCKETS = 1024;

    private static class RateLimitBucket {
        final Deque<Long> attempts = new ArrayDeque<>();
        long lockedUntil = 0L;
    }

    /**
     * Handle auth API requests.
     */
    public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        return handle(method, path, body, out, null);
    }

    /**
     * Handle auth API requests with rate-limit identity (X-Forwarded-For or socket).
     */
    public static boolean handle(String method, String path, String body, OutputStream out,
                                  String rateLimitIdentity) throws Exception {

        if (path.equals("/auth/status") && method.equals("GET")) {
            return handleStatus(out);
        }

        if (path.equals("/auth/token") && method.equals("POST")) {
            // Rate-limit token validation to slow down brute-force attempts
            // through public tunnels. Identity prefers X-Forwarded-For so a
            // tunnel attacker can't share a bucket with the loopback caller.
            String idForLimit = (rateLimitIdentity != null && !rateLimitIdentity.isEmpty())
                ? rateLimitIdentity : "unknown";
            String rateError = checkRateLimit(idForLimit);
            if (rateError != null) {
                JSONObject resp = new JSONObject();
                resp.put("success", false);
                resp.put("error", rateError);
                HttpResponse.sendJson(out, resp.toString());
                return true;
            }
            return handleTokenValidation(body, out, idForLimit);
        }

        if (path.equals("/auth/logout") && method.equals("POST")) {
            return handleLogout(out);
        }

        return false;
    }

    /**
     * @return null if request may proceed, error string if rate limited.
     */
    private static String checkRateLimit(String identity) {
        long now = System.currentTimeMillis();
        // Opportunistic GC to bound the map under public-tunnel scanner load.
        // Runs on every Nth invocation so the steady-state cost is negligible.
        if ((rateLimitOps.incrementAndGet() & (RATE_LIMIT_GC_EVERY - 1)) == 0) {
            pruneStaleRateLimits(now);
        }
        RateLimitBucket bucket = rateLimits.computeIfAbsent(identity, k -> new RateLimitBucket());
        synchronized (bucket) {
            if (bucket.lockedUntil > now) {
                long secs = (bucket.lockedUntil - now) / 1000 + 1;
                return Messages.get("errors.rate_limited_locked_for_seconds", secs);
            }
            // Drop attempts outside the window
            long windowStart = now - RATE_LIMIT_WINDOW_MS;
            while (!bucket.attempts.isEmpty() && bucket.attempts.peekFirst() < windowStart) {
                bucket.attempts.pollFirst();
            }
            if (bucket.attempts.size() >= RATE_LIMIT_MAX_ATTEMPTS) {
                bucket.lockedUntil = now + RATE_LIMIT_LOCKOUT_MS;
                bucket.attempts.clear();
                log("Rate limit exceeded for " + identity + " — locked for "
                    + (RATE_LIMIT_LOCKOUT_MS / 1000) + "s");
                return Messages.get("errors.rate_limited_locked_for_seconds", (RATE_LIMIT_LOCKOUT_MS / 1000));
            }
            bucket.attempts.addLast(now);
        }
        return null;
    }

    /**
     * Reset the rate-limit bucket for an identity after a successful login.
     */
    private static void clearRateLimit(String identity) {
        if (identity != null) rateLimits.remove(identity);
    }

    /**
     * Walks the rateLimits map and removes buckets that are no longer
     * useful: lockout has expired AND the attempt window holds no entries.
     * Then enforces RATE_LIMIT_MAX_BUCKETS as a hard ceiling by dropping
     * the oldest-by-attempt bucket if we're over the cap. CHM iteration is
     * weakly consistent and the per-bucket synchronized block in
     * checkRateLimit keeps state-machine consistency, so concurrent
     * iteration here is safe.
     */
    private static void pruneStaleRateLimits(long now) {
        long stalenessCutoff = now - RATE_LIMIT_LOCKOUT_MS - RATE_LIMIT_WINDOW_MS;
        java.util.Iterator<java.util.Map.Entry<String, RateLimitBucket>> it =
            rateLimits.entrySet().iterator();
        while (it.hasNext()) {
            java.util.Map.Entry<String, RateLimitBucket> e = it.next();
            RateLimitBucket b = e.getValue();
            synchronized (b) {
                // Only drop buckets that have a HISTORY (most recent attempt
                // older than stalenessCutoff) or a fully-expired lockout.
                // Critical: do NOT drop a bucket that is empty + lockedUntil=0
                // — that's the freshly-created state from computeIfAbsent
                // before the caller has entered its synchronized block. The
                // caller (checkRateLimit) holds the bucket reference but the
                // map lookup hasn't been synchronized; if we evict here, the
                // caller adds attempts to an orphaned bucket and the next
                // checkRateLimit for the same identity creates a new bucket,
                // silently losing the rate-limit state. Brute-force attackers
                // could time-attack the prune cadence to bypass protection.
                if (b.lockedUntil > 0 && b.lockedUntil < now && b.attempts.isEmpty()) {
                    it.remove();
                } else if (!b.attempts.isEmpty() && b.attempts.peekLast() < stalenessCutoff
                        && b.lockedUntil < now) {
                    it.remove();
                }
            }
        }
        // Hard cap. Under a sustained scanner burst the prune above may not
        // keep up; this enforces a ceiling regardless of bucket recency.
        if (rateLimits.size() > RATE_LIMIT_MAX_BUCKETS) {
            int over = rateLimits.size() - RATE_LIMIT_MAX_BUCKETS;
            java.util.Iterator<java.util.Map.Entry<String, RateLimitBucket>> dropIt =
                rateLimits.entrySet().iterator();
            while (over > 0 && dropIt.hasNext()) {
                dropIt.next();
                dropIt.remove();
                over--;
            }
        }
    }
    
    /**
     * GET /auth/status
     * Returns device info.
     */
    private static boolean handleStatus(OutputStream out) throws Exception {
        AuthManager.AuthState state = AuthManager.getState();
        
        JSONObject response = new JSONObject();
        response.put("status", "ok");
        
        if (state != null) {
            response.put("deviceId", state.deviceId);
        } else {
            response.put("deviceId", "unknown");
        }
        
        HttpResponse.sendJson(out, response.toString());
        return true;
    }
    
    /**
     * POST /auth/token
     * Validates device token and returns JWT session.
     */
    private static boolean handleTokenValidation(String body, OutputStream out, String rateLimitIdentity) throws Exception {
        JSONObject response = new JSONObject();

        try {
            JSONObject request = new JSONObject(body);
            String token = request.optString("token", "");

            boolean valid = AuthManager.validateDeviceToken(token);

            if (valid) {
                // Successful login — wipe attempt counter so the user gets a
                // fresh 10-attempt budget on their next session.
                clearRateLimit(rateLimitIdentity);

                String jwt = AuthManager.generateJwt();
                AuthManager.AuthState state = AuthManager.getState();
                if (jwt == null || state == null) {
                    // Auth state was invalidated between validateDeviceToken
                    // and here (e.g. concurrent regenerateToken). Treat as
                    // a transient failure rather than NPEing on state.deviceId.
                    response.put("success", false);
                    response.put("error", Messages.get("errors.invalid_device_token"));
                    log("Auth state vanished mid-login — asking client to retry");
                } else {
                    response.put("success", true);
                    response.put("jwt", jwt);
                    response.put("deviceId", state.deviceId);
                    response.put("expiresIn", AuthManager.JWT_EXPIRY_SECONDS);

                    log("Token validated for device: " + state.deviceId);
                }
            } else {
                response.put("success", false);
                response.put("error", Messages.get("errors.invalid_device_token"));
                log("Invalid token attempt from " + rateLimitIdentity);
            }

        } catch (Exception e) {
            response.put("success", false);
            response.put("error", Messages.get("errors.invalid_request_with_detail", e.getMessage()));
        }

        HttpResponse.sendJson(out, response.toString());
        return true;
    }
    
    /**
     * POST /auth/logout
     * Logs out the user. Client should clear stored JWT.
     */
    private static boolean handleLogout(OutputStream out) throws Exception {
        JSONObject response = new JSONObject();
        response.put("success", true);
        response.put("message", Messages.get("messages.logged_out"));
        
        HttpResponse.sendJson(out, response.toString());
        return true;
    }
    
    private static void log(String message) {
        CameraDaemon.log("AUTH: " + message);
    }
}

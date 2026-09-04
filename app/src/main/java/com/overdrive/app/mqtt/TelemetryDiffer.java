package com.overdrive.app.mqtt;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Report-by-exception change detector for a single transmit destination
 * (one MQTT connection, or the ABRP uploader).
 *
 * Holds the last-transmitted value of every key and decides, each cycle:
 *   - which keys changed beyond their per-key deadband ({@link TelemetryFieldCatalog}), and
 *   - whether a transmit should happen at all, given a min-interval floor
 *     ("never faster than") and a max-interval heartbeat ("never slower than").
 *
 * Time / monotonic keys ({@link TelemetryFieldCatalog#EXCLUDED}) are ignored for
 * change purposes — otherwise the ever-incrementing {@code utc} would make every
 * payload look "changed" and defeat the whole feature.
 *
 * Not thread-safe; each instance is owned by a single publisher/uploader thread.
 */
public class TelemetryDiffer {

    /** key -> stable quantized representation of the last sent value. */
    private final Map<String, String> lastSent = new HashMap<>();
    private long lastSendTimeMs = 0;
    // Time of the last FULL resync (every discoverable key sent), tracked separately from
    // lastSendTimeMs so the heartbeat can fire on its own fixed cadence without being reset
    // by intervening change-only partial publishes.
    private long lastFullSyncMs = 0;

    /** Forget all history — forces a full resend on the next cycle. */
    public void reset() {
        lastSent.clear();
        lastSendTimeMs = 0;
        lastFullSyncMs = 0;
    }

    public long lastSendTimeMs() { return lastSendTimeMs; }

    public long elapsedMs(long nowMs) {
        return lastSendTimeMs == 0 ? Long.MAX_VALUE : nowMs - lastSendTimeMs;
    }

    /** Time since the last full resync (every key sent). MAX_VALUE if never. */
    public long fullSyncElapsedMs(long nowMs) {
        return lastFullSyncMs == 0 ? Long.MAX_VALUE : nowMs - lastFullSyncMs;
    }

    /** Record that a full resync (every discoverable key) was just transmitted. */
    public void markFullSync(long nowMs) { lastFullSyncMs = nowMs; }

    /** Keys whose quantized value differs from what we last sent (excludes time keys). */
    public Set<String> changedKeys(JSONObject snapshot) {
        Set<String> changed = new HashSet<>();
        Iterator<String> it = snapshot.keys();
        while (it.hasNext()) {
            String k = it.next();
            if (TelemetryFieldCatalog.EXCLUDED.contains(k)) continue;
            String now = repr(k, snapshot.opt(k));
            String prev = lastSent.get(k);
            if (prev == null || !prev.equals(now)) changed.add(k);
        }
        return changed;
    }

    /**
     * Should we transmit this cycle?
     *
     * @param anyChanged    whether at least one trigger field changed
     * @param changeOnly    if false, behave like a plain interval publisher (always at min cadence)
     */
    public boolean shouldPublish(boolean anyChanged, boolean changeOnly,
                                 long nowMs, long minIntervalMs, long maxIntervalMs) {
        long elapsed = elapsedMs(nowMs);
        if (lastSendTimeMs == 0) return true;                 // first publish
        if (elapsed >= maxIntervalMs) return true;            // heartbeat ceiling
        if (elapsed < minIntervalMs) return false;            // rate-limit floor
        if (!changeOnly) return true;                         // plain interval mode
        return anyChanged;                                    // change within [min, max)
    }

    /** Record that the entire snapshot was just transmitted. */
    public void markAllSent(JSONObject snapshot, long nowMs) {
        Iterator<String> it = snapshot.keys();
        while (it.hasNext()) {
            String k = it.next();
            lastSent.put(k, repr(k, snapshot.opt(k)));
        }
        lastSendTimeMs = nowMs;
    }

    /** Record that a specific subset of keys was just transmitted (per-field mode). */
    public void markKeysSent(JSONObject snapshot, Set<String> keys, long nowMs) {
        for (String k : keys) {
            lastSent.put(k, repr(k, snapshot.opt(k)));
        }
        lastSendTimeMs = nowMs;
    }

    /**
     * Stable, equality-friendly representation of a value after quantization.
     * Numeric values are bucketed by the catalog deadband (or 2 dp / exact-int by
     * default) so floating-point noise does not register as a change.
     */
    static String repr(String key, Object v) {
        if (v == null || v == JSONObject.NULL) return "∅";
        if (v instanceof Number) {
            double d = ((Number) v).doubleValue();
            double step = TelemetryFieldCatalog.precisionFor(key);
            if (step > 0) {
                return "q" + Math.round(d / step);
            }
            if (v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof Byte) {
                return Long.toString(((Number) v).longValue());
            }
            return "q" + Math.round(d * 100); // default 2-decimal deadband for untyped doubles
        }
        return v.toString(); // boolean / string / JSONArray
    }
}

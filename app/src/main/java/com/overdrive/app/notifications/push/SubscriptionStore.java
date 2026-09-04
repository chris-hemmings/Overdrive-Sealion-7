package com.overdrive.app.notifications.push;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Persistent push-subscription store. JSON file at the configured path.
 *
 * <p>Concurrency model: all mutations are serialized via a single monitor on
 * the instance. Reads return defensive copies so iteration in {@code PushSink}
 * doesn't race with subscribe/unsubscribe.
 */
public final class SubscriptionStore {

    private final File file;
    private final Map<String, PushSubscription> byId = new LinkedHashMap<>();
    private final AtomicBoolean loaded = new AtomicBoolean(false);

    /**
     * Recently-unsubscribed ids → expiry epoch ms. Stops a subscribe POST from
     * silently re-creating a row the user just removed. Without this, the
     * user's removal is undone the next time the device's PWA does anything
     * that calls /api/push/subscribe (Enable click, requestAndSubscribe, etc.)
     * because the live PushSubscription on the browser is still valid.
     */
    private final Map<String, Long> tombstones = new LinkedHashMap<>();
    public static final long TOMBSTONE_WINDOW_MS = 30L * 60 * 1000;
    private static final int MAX_TOMBSTONES = 200;
    /**
     * Debounced lastSeenAt persistence. {@link #touchLastSeen} updates the
     * in-memory field on every call but only schedules a flush at most once
     * per {@link #LAST_SEEN_FLUSH_INTERVAL_MS}. Without this, the previous
     * pattern (mutate sub.lastSeenAt without persisting) lost the timestamp
     * across daemon restart; persisting on every send would put a file write
     * in the push hot path.
     */
    private static final long LAST_SEEN_FLUSH_INTERVAL_MS = 60_000L;
    private long lastSeenLastFlushMs = 0;

    public SubscriptionStore(File file) {
        this.file = file;
    }

    public synchronized void load() {
        if (loaded.get()) return;
        loaded.set(true);

        if (!file.exists() || file.length() == 0) return;
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] bytes = readAll(fis);
            String raw = new String(bytes, "UTF-8");
            // The on-disk format used to be a bare JSONArray of subscriptions.
            // We now wrap as {subs, tombstones} so a single file holds both.
            // Detect by first non-whitespace char and migrate transparently.
            int i = 0;
            while (i < raw.length() && Character.isWhitespace(raw.charAt(i))) i++;
            char first = i < raw.length() ? raw.charAt(i) : '\0';
            JSONArray subsArr = null;
            JSONObject tombObj = null;
            if (first == '[') {
                // legacy bare-array
                subsArr = new JSONArray(raw);
            } else if (first == '{') {
                JSONObject root = new JSONObject(raw);
                subsArr = root.optJSONArray("subs");
                tombObj = root.optJSONObject("tombstones");
            }
            if (subsArr != null) {
                for (int k = 0; k < subsArr.length(); k++) {
                    try {
                        PushSubscription sub = PushSubscription.fromJson(subsArr.getJSONObject(k));
                        byId.put(sub.id, sub);
                    } catch (Exception e) {
                        // skip corrupt entry, continue loading the rest
                    }
                }
            }
            if (tombObj != null) {
                long now = System.currentTimeMillis();
                java.util.Iterator<String> it = tombObj.keys();
                while (it.hasNext()) {
                    String id = it.next();
                    long expiry = tombObj.optLong(id, 0);
                    if (expiry > now) tombstones.put(id, expiry);
                }
            }
        } catch (Exception ignored) {}
    }

    public synchronized List<PushSubscription> all() {
        if (!loaded.get()) load();
        return Collections.unmodifiableList(new ArrayList<>(byId.values()));
    }

    public synchronized PushSubscription get(String id) {
        if (!loaded.get()) load();
        return byId.get(id);
    }

    public synchronized void put(PushSubscription sub) {
        if (!loaded.get()) load();
        byId.put(sub.id, sub);
        persist();
    }

    /**
     * User-driven removal — POSTed via /api/push/unsubscribe. Adds a
     * tombstone so silent self-heal paths (pwa-init.js init() with a live
     * browser PushSubscription) can't immediately resurrect the row.
     */
    public synchronized boolean remove(String id) {
        if (!loaded.get()) load();
        boolean removed = byId.remove(id) != null;
        if (removed) {
            tombstones.put(id, System.currentTimeMillis() + TOMBSTONE_WINDOW_MS);
            evictExcessTombstones();
            persist();
        }
        return removed;
    }

    /**
     * System-driven removal — push service told us the subscription is
     * gone (404/410 from FCM/Mozilla). The user did NOT ask to disable;
     * the browser dropped its sub for unrelated reasons (Samsung Internet
     * across app updates, FCM token refresh on Chrome, etc.). We must NOT
     * tombstone — pwa-init.js's silent self-heal needs to be able to
     * subscribe again immediately. Tombstoning here would lock the user
     * into a 30-minute "permission granted, no sub" hole.
     */
    public synchronized boolean removeExpired(String id) {
        if (!loaded.get()) load();
        boolean removed = byId.remove(id) != null;
        if (removed) persist();
        return removed;
    }

    /**
     * Server-side guard against re-subscribe immediately after the user
     * removed a device. Returns true while the tombstone is active.
     * Auto-expires after {@link #TOMBSTONE_WINDOW_MS}.
     */
    public synchronized boolean isTombstoned(String id) {
        if (!loaded.get()) load();
        Long expiry = tombstones.get(id);
        if (expiry == null) return false;
        if (expiry > System.currentTimeMillis()) return true;
        // Lazy expiry — drop and persist next mutation.
        tombstones.remove(id);
        return false;
    }

    /** Discard the tombstone — used when the user explicitly re-enables. */
    public synchronized void clearTombstone(String id) {
        if (!loaded.get()) load();
        if (tombstones.remove(id) != null) persist();
    }

    /**
     * Update {@code lastSeenAt} on the in-memory entry. Persists at most once
     * per {@link #LAST_SEEN_FLUSH_INTERVAL_MS} so we don't put a file write
     * in the push hot path. Daemon restart in the gap forfeits the unsynced
     * delta, which is acceptable — lastSeenAt is informational only.
     */
    public synchronized void touchLastSeen(String id, long whenMs) {
        if (!loaded.get()) load();
        PushSubscription s = byId.get(id);
        if (s == null) return;
        s.lastSeenAt = whenMs;
        if (whenMs - lastSeenLastFlushMs >= LAST_SEEN_FLUSH_INTERVAL_MS) {
            lastSeenLastFlushMs = whenMs;
            persist();
        }
    }

    public synchronized int size() {
        if (!loaded.get()) load();
        return byId.size();
    }

    /**
     * Bound the tombstone map. Without this, a user that repeatedly
     * subscribes + removes can grow the file unboundedly. We keep the most
     * recent {@link #MAX_TOMBSTONES} entries — old ones expire naturally
     * via {@link #TOMBSTONE_WINDOW_MS} anyway.
     */
    private void evictExcessTombstones() {
        if (tombstones.size() <= MAX_TOMBSTONES) return;
        java.util.Iterator<Map.Entry<String, Long>> it = tombstones.entrySet().iterator();
        while (tombstones.size() > MAX_TOMBSTONES && it.hasNext()) {
            it.next();
            it.remove();
        }
    }

    // ==================== INTERNAL ====================

    private void persist() {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();

        JSONArray arr = new JSONArray();
        for (PushSubscription sub : byId.values()) arr.put(sub.toJson());

        // v2 format: {subs, tombstones}. Older daemons that pre-date this
        // change parse the new file as JSONObject and the legacy bare-array
        // branch in load() now handles both. Forward-compatible.
        JSONObject root = new JSONObject();
        try {
            root.put("subs", arr);
            // Drop expired tombstones before writing so the file doesn't
            // accumulate stale ids across many remove cycles.
            long now = System.currentTimeMillis();
            JSONObject tombObj = new JSONObject();
            java.util.Iterator<Map.Entry<String, Long>> it = tombstones.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Long> e = it.next();
                if (e.getValue() <= now) {
                    it.remove();
                    continue;
                }
                tombObj.put(e.getKey(), e.getValue());
            }
            if (tombObj.length() > 0) root.put("tombstones", tombObj);
        } catch (Exception e) {
            // JSON construction failed — fall back to the bare-array shape
            // so we never leave the file unreadable.
        }

        File tmp = new File(file.getAbsolutePath() + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(tmp)) {
            fos.write(root.toString().getBytes("UTF-8"));
            fos.getFD().sync();
        } catch (Exception e) {
            // Couldn't even write the tmp; leave the existing file intact.
            return;
        }
        // Atomic-rename happy path. On filesystems where rename-overwrite
        // isn't supported, fall through to the swap dance below.
        if (tmp.renameTo(file)) return;

        // Swap dance: keep a backup so a second-rename failure doesn't
        // leave us with zero subscriptions. Previously this path did
        //     file.delete(); tmp.renameTo(file);
        // and a second-rename failure (volume unmount, permission flip)
        // wiped every subscription on next boot.
        File backup = new File(file.getAbsolutePath() + ".bak");
        backup.delete();
        boolean haveBackup = file.renameTo(backup);
        if (!tmp.renameTo(file)) {
            // tmp couldn't move into place. Restore from backup so we
            // don't end up with no subscriptions on disk.
            if (haveBackup) backup.renameTo(file);
            // Leave tmp on disk; load() ignores it.
            return;
        }
        // New file is in place; remove the backup. Best-effort.
        if (haveBackup) backup.delete();
    }

    private static byte[] readAll(FileInputStream fis) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = fis.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    /**
     * Derive a stable subscription id from the endpoint URL. The endpoint
     * itself is large and contains opaque tokens — we hash it so the id
     * stays compact and consistent across re-subscribe attempts.
     */
    public static String idForEndpoint(String endpoint) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(endpoint.getBytes("UTF-8"));
            return android.util.Base64.encodeToString(
                    java.util.Arrays.copyOf(digest, 12),
                    android.util.Base64.URL_SAFE | android.util.Base64.NO_PADDING | android.util.Base64.NO_WRAP);
        } catch (Exception e) {
            return Integer.toHexString(endpoint.hashCode());
        }
    }
}

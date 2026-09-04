package com.overdrive.app.notifications;

import org.json.JSONObject;

/**
 * Canonical notification event. Emitted by sources (surveillance, tyre,
 * proximity, etc.) and consumed by sinks (PushSink, LogSink).
 *
 * <p>Category is a dotted string ("surveillance.motion",
 * "vehicle.health.tyre.leak"), not an enum, so future categories can be added
 * by registry config without code changes here.
 *
 * <p>Severity drives the UI rendering on the client (vibrate pattern,
 * requireInteraction, sound). For categories with {@code "severity": "auto"}
 * in the registry, the source should compute it per-event from the data.
 */
public final class NotificationEvent {

    public enum Severity { INFO, WARN, CRITICAL }

    public final String category;
    public final Severity severity;
    public final String title;
    public final String body;
    public final long timestamp;
    /** Server-side dedupe key. Two events with the same tag collapse on display. May be null. */
    public final String tag;
    /** Click target URL. If null, sink falls back to the registry's defaultClickUrl. */
    public final String clickUrl;
    /** Category-specific extras (filename, wheel index, kPa, etc.). Never null. */
    public final JSONObject data;

    /**
     * Transient delivery hint — NOT serialized into the push payload and NOT
     * persisted by HistorySink. When true, {@link
     * com.overdrive.app.notifications.sinks.PushSink} skips Web Push for this
     * event; every other sink (HistorySink persistence, LogSink diagnostics,
     * TelegramSink) still processes it normally.
     *
     * <p>This lets a source that owns a push-tier preference (e.g. surveillance
     * motion, whose per-tier toggle would otherwise decide delivery) still
     * publish EVERY event to the bus — so the Notification Log records it — while
     * honoring the tier choice for Web Push only. Set once by the publisher
     * BEFORE {@link NotificationBus#publish}; read only by sinks on the single
     * bus dispatch thread, so the executor-submit happens-before edge makes it
     * visible with no extra synchronization. Default false = deliver normally.
     */
    private boolean pushSuppressed = false;

    /**
     * Transient delivery hint — NOT serialized, NOT persisted. When true,
     * {@link com.overdrive.app.notifications.sinks.PushSink} delivers this event
     * to every subscription REGARDLESS of that device's muted categories,
     * severity floor, or quiet hours.
     *
     * <p>Reserved for user-initiated diagnostics — the "Send test" button, whose
     * entire purpose is "does Web Push reach this device at all". Gating a test on
     * the very preferences the user is trying to verify would make the button
     * appear broken (e.g. on a fresh device where the default category is muted by
     * default, or when the user has raised the severity floor). Mutually exclusive
     * in practice with {@link #pushSuppressed}; if both were somehow set,
     * suppression wins (checked first in PushSink). Default false = honour prefs.
     */
    private boolean preferencesBypassed = false;

    /**
     * Transient delivery hint — NOT serialized, NOT persisted. When non-null,
     * {@link com.overdrive.app.notifications.sinks.PushSink} delivers this event
     * ONLY to the subscription whose id matches, skipping every other device.
     *
     * <p>Reserved for the user-initiated "Send test" — a test must land on the
     * phone that asked for it, not fan out to every registered device (which,
     * combined with {@link #preferencesBypassed}, would otherwise buzz a
     * sleeping phone in its quiet hours). Null = deliver to all eligible
     * subscriptions, the normal broadcast behaviour.
     */
    private String pushTargetId = null;

    public NotificationEvent(String category, Severity severity, String title, String body,
                             String tag, String clickUrl, JSONObject data) {
        if (category == null) throw new IllegalArgumentException("category required");
        if (severity == null) throw new IllegalArgumentException("severity required");
        if (title == null) throw new IllegalArgumentException("title required");
        this.category = category;
        this.severity = severity;
        this.title = title;
        this.body = body == null ? "" : body;
        this.timestamp = System.currentTimeMillis();
        this.tag = tag;
        this.clickUrl = clickUrl;
        this.data = data == null ? new JSONObject() : data;
    }

    /**
     * Mark this event as Web-Push-suppressed (see {@link #pushSuppressed}) and
     * return {@code this} so publishers can chain it onto construction:
     * {@code bus.publish(new NotificationEvent(...).suppressPush())}. Affects
     * only PushSink; persistence/log/Telegram sinks are unaffected.
     */
    public NotificationEvent suppressPush() {
        this.pushSuppressed = true;
        return this;
    }

    /** @return true if PushSink should skip Web Push for this event. */
    public boolean isPushSuppressed() {
        return pushSuppressed;
    }

    /**
     * Mark this event to bypass per-device push preferences (mute / severity
     * floor / quiet hours) — see {@link #preferencesBypassed}. Returns {@code
     * this} for chaining. For user-initiated diagnostics only.
     */
    public NotificationEvent bypassPreferences() {
        this.preferencesBypassed = true;
        return this;
    }

    /** @return true if PushSink should ignore this device's mute/floor/quiet-hours. */
    public boolean isPreferencesBypassed() {
        return preferencesBypassed;
    }

    /**
     * Restrict this event to a single subscription id (see {@link #pushTargetId}).
     * Returns {@code this} for chaining. Null-safe: a null/empty id is ignored
     * (falls back to broadcast).
     */
    public NotificationEvent targetSubscription(String subscriptionId) {
        if (subscriptionId != null && !subscriptionId.isEmpty()) {
            this.pushTargetId = subscriptionId;
        }
        return this;
    }

    /** @return the single subscription id to deliver to, or null to broadcast. */
    public String getPushTargetId() {
        return pushTargetId;
    }

    /** Build the wire envelope sent inside the encrypted Web Push payload. */
    public JSONObject toPayloadJson() {
        JSONObject j = new JSONObject();
        try {
            j.put("v", 1);
            j.put("category", category);
            j.put("severity", severity.name().toLowerCase(java.util.Locale.US));
            j.put("title", title);
            j.put("body", body);
            j.put("ts", timestamp);
            if (tag != null) j.put("tag", tag);
            if (clickUrl != null) j.put("url", clickUrl);
            j.put("data", data);
        } catch (Exception ignored) {}
        return j;
    }
}

package com.overdrive.app.notifications.sinks;

import com.overdrive.app.notifications.CategoryRegistry;
import com.overdrive.app.notifications.NotificationBus;
import com.overdrive.app.notifications.NotificationEvent;
import com.overdrive.app.notifications.NotificationStore;

/**
 * Persistence sink — writes every {@link NotificationEvent} to the
 * {@link NotificationStore} so the Notifications ▸ Log tab has a durable history
 * to browse, filter, and delete.
 *
 * <p>Subscribed FIRST (before LogSink / PushSink / TelegramSink) in
 * {@code CameraDaemon.initNotifications()} so it receives the NotificationBus
 * pre-subscribe buffer flush — the bus replays boot-window events only to the
 * first sink that subscribes, and those edges (a door opened / charging fault
 * during startup) are exactly what a user expects to find in the log.
 *
 * <p>Because it sits on the bus (not at the ~13 individual publisher sites),
 * EVERY notification — surveillance, proximity, tyre, door, charging, SOH,
 * test — is captured with no per-source change. Dispatch is already off the
 * caller thread (NotificationBus uses a single-thread executor), and the
 * store's insert is fully guarded, so this sink adds no latency to the
 * publisher.
 */
public final class HistorySink implements NotificationBus.Sink {

    private final NotificationStore store;
    private final CategoryRegistry registry;

    public HistorySink(NotificationStore store, CategoryRegistry registry) {
        this.store = store;
        this.registry = registry;
    }

    @Override
    public void onNotification(NotificationEvent event) {
        if (store == null) return;
        // Resolve the registry's defaultClickUrl for categories that publish a
        // null clickUrl (charging/door/tyre/SOH), mirroring PushSink, so their
        // Log-tab rows deep-link to the right settings page. Surveillance /
        // proximity already carry their own /events.html?…&file= clickUrl and
        // are unaffected (insert() prefers the explicit URL).
        String fallbackUrl = null;
        try {
            if (registry != null && event != null) {
                CategoryRegistry.Entry entry = registry.get(event.category);
                if (entry != null) fallbackUrl = entry.defaultClickUrl;
            }
        } catch (Throwable ignored) { /* never let URL resolution drop the row */ }
        store.insert(event, fallbackUrl);
    }
}

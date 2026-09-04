package com.overdrive.app.automation.condition;

import com.overdrive.app.automation.Automations;
import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.monitor.BluetoothMonitor;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Publishes Bluetooth connection state / connected-device name into the automation
 * state so btState and btDeviceName triggers can be evaluated.
 *
 * <p>A deliberate twin of {@link NetworkEvent} (WiFi): same low cadence, same
 * self-gating, same edge semantics. The design invariants that matter:
 * <ul>
 *   <li><b>Zero cost when disabled.</b> The poll reschedules itself every
 *       {@link #POLL_SECONDS}s but does nothing — no Bluetooth read, no shell — when
 *       no automation is enabled ({@link Automations#isDisabled()} is an O(1) field
 *       read). An idle feature costs only a parked scheduler thread. This is the same
 *       bar the rest of the automation subsystem holds.</li>
 *   <li><b>State is an edge.</b> {@link Automations#update} only fires evaluation on a
 *       real value transition, so republishing the same device name every poll is
 *       free and a "when my phone connects" automation fires once per connect.</li>
 *   <li><b>Never null.</b> The device name is published as "" (never null) when
 *       disconnected so an unset value can't be mistaken for "unseen" and NPE a
 *       downstream comparison.</li>
 * </ul>
 */
public final class BluetoothEvent {
    private static final DaemonLogger logger = DaemonLogger.getInstance("Automations");
    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private static final AtomicReference<ScheduledFuture<?>> active = new AtomicReference<>();

    // Poll cadence. Matches WiFi: long enough to be cheap, short enough that a
    // "when Bluetooth connects" automation feels responsive.
    private static final int POLL_SECONDS = 20;

    private BluetoothEvent() {}

    public static void scheduleBluetoothEvent() {
        ScheduledFuture<?> next = scheduler.schedule(BluetoothEvent::poll, POLL_SECONDS, TimeUnit.SECONDS);
        ScheduledFuture<?> previous = active.getAndSet(next);
        if (previous != null && !previous.isDone()) {
            previous.cancel(false);
        }
    }

    private static void poll() {
        try {
            // Skip all work (including the Bluetooth refresh) when nothing is listening.
            if (!Automations.isDisabled()) {
                publishBluetooth();
            }
        } catch (Throwable t) {
            logger.error("Failed to run bluetooth event", t);
        } finally {
            // Always reschedule so the poll resumes once an automation is enabled.
            scheduleBluetoothEvent();
        }
    }

    private static void publishBluetooth() {
        // Refresh from the OS (Android API, shell fallback under UID 2000), then
        // publish the (possibly changed) state.
        BluetoothMonitor.refresh();
        boolean connected = BluetoothMonitor.isConnected();
        Automations.update(BydEvent.BT_STATE, connected ? "on" : "off");
        // Publish the device name (empty string when not connected) so a name-match
        // condition sees a stable value. Never publish null.
        String name = BluetoothMonitor.getDeviceName();
        Automations.update(BydEvent.BT_DEVICE_NAME, name == null ? "" : name);
    }
}

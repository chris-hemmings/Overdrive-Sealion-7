package com.overdrive.app.power;

import android.content.Context;

import com.overdrive.app.byd.BydDeviceHelper;
import com.overdrive.app.logging.DaemonLogger;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * Read-only helpers for BYD HAL signals that are LIVE while ACC=OFF.
 *
 * <p>Inventoried via {@code /api/debug/autoservice/probe-getters} on a parked
 * DiLink 3.0 (project_acc_off_probe_inventory.md). The probe ran 634 no-arg
 * getters across 18 BYD device classes; these are the ones that returned
 * actionable values when the car was asleep.
 *
 * <p><b>Nothing in Overdrive calls these yet.</b> The class exists so future
 * code (surveillance arming, diagnostic UI, cloud auth) can opt in without
 * having to re-discover which signals work ACC=OFF.
 *
 * <p>All readers:
 * <ul>
 *   <li>Are static, stateless, side-effect-free.</li>
 *   <li>Take a {@link Context} (a {@code PermissionBypassContext} on the
 *       acc_sentry path; the cam_daemon's collector handles its own).</li>
 *   <li>Return boxed values so unavailable signals can return {@code null}
 *       without ambiguity. Sentinel ints like {@code -10011} (INVALID_VALUE)
 *       are normalised to {@code null} where they don't carry meaning.</li>
 *   <li>Cache the underlying device handle per-Context for the lifetime
 *       of the process — same trick as {@link McuPowerHal}.</li>
 * </ul>
 *
 * <p>FQNs are verified at runtime against the DiLink 3.0 trim. If you target a
 * newer/older trim and a class moves, a single reader returns null silently;
 * the rest keep working.
 *
 * <p>For listener-driven signals (e.g. {@code onElecPercentageChanged} for SoC,
 * {@code onBatteryPowerVoltageChanged} for 12V), prefer the existing monitor
 * classes ({@code BatterySocMonitor}, {@code BatteryPowerMonitor}). This
 * helper is for one-shot polls.
 */
public final class AccOffReaders {

    private static final String TAG = "AccOffReaders";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    /** Sentinel returned by BYD HAL when no recent CAN frame was cached. */
    public static final int INVALID_VALUE = -10011;

    /** BODYWORK_COMMAND_FAILED — also returned by some getters on failure. */
    public static final int COMMAND_FAILED = -2147482648;

    /** BODYWORK_COMMAND_INVALID_VALUE. */
    public static final int COMMAND_INVALID_VALUE = -2147482645;

    // Cached device handles. Keyed by FQN. Reset by {@link #invalidateDeviceCache}.
    private static final Map<String, Object> deviceCache = new HashMap<>();
    private static volatile Context cachedContext;

    private AccOffReaders() {}

    // ── Security / arming ──────────────────────────────────────────

    /**
     * Anti-theft alarm state. Live ACC=OFF.
     * <p>Probed value on the test car: {@code 1} (alarm ON, car armed).
     * <p>Source: {@code BYDAutoBodyworkDevice.getAlarmState()}.
     *
     * @return alarm state code, or {@code null} if unavailable.
     */
    public static Integer getAlarmState(Context ctx) {
        return readInt(ctx, BODYWORK, "getAlarmState");
    }

    /**
     * Left-front door lock state. {@code 0=INVALID, 1=UNLOCK, 2=LOCK}
     * (per {@code AbsBYDAutoDoorLockListener} stub).
     * <p>This works ACC=OFF where {@code Setting.getDoorLock} doesn't —
     * the OTA device caches the LF lock signal even with the BCM asleep.
     * <p>Probed value on test car: {@code 2} (locked).
     * <p>Source: {@code BYDAutoOtaDevice.getLFDoorLockState()}.
     *
     * @return one of {@code 0/1/2}, or {@code null} if unavailable.
     */
    public static Integer getLfDoorLockState(Context ctx) {
        return readInt(ctx, OTA, "getLFDoorLockState");
    }

    /**
     * Right-front / rear door lock states.
     *
     * <p><b>NOT EXPOSED on DiLink 3.0.</b> Verified 2026-06-03 by probing —
     * {@code BYDAutoOtaDevice} only declares {@code getLFDoorLockState};
     * RF/LR/RR variants throw {@code NoSuchMethod}. For all-four-door state
     * use {@code BydCloudDataProvider} which surfaces them via the BYD
     * realtime-lock MQTT topic.
     *
     * <p>Kept as no-op stubs returning {@code null} so callers don't need
     * to special-case "single-door trim" — they can just call all four and
     * gracefully ignore nulls if the trim ever exposes them.
     */
    public static Integer getRfDoorLockState(Context ctx) { return null; }
    public static Integer getLrDoorLockState(Context ctx) { return null; }
    public static Integer getRrDoorLockState(Context ctx) { return null; }

    /**
     * Vehicle composite state from the Setting device.
     * <p>Probed value on test car: {@code 0} (parked). Exact enum semantics
     * are BYD-private; treat as opaque for now.
     */
    public static Integer getVehicleState(Context ctx) {
        return readInt(ctx, SETTING, "getVehicleState");
    }

    /**
     * Start-button / key-fob action state.
     * <p>Probed value on test car: {@code 0} (no recent press).
     */
    public static Integer getStartKeyState(Context ctx) {
        return readInt(ctx, SETTING, "getStartKeyState");
    }

    /** Electric handbrake state (engaged?). */
    public static Integer getElecHandbrakeState(Context ctx) {
        return readInt(ctx, SETTING, "getElecHandbrakeState");
    }

    // ── Power / battery ────────────────────────────────────────────

    /**
     * MCU status. {@code 0=sleeping, 1=active, 2=ACC-off, 3=deep-sleep}.
     * Useful for verifying that {@link McuPowerHal#requestMcuSleep} actually
     * moves the state.
     * <p>Source: {@code BYDAutoPowerDevice.getMcuStatus()}.
     */
    public static Integer getMcuStatus(Context ctx) {
        return readInt(ctx, POWER, "getMcuStatus");
    }

    /**
     * Remaining EV battery power (kWh-ish — exact units are SDK-private).
     * <p>Probed value on test car: {@code 17.1}.
     * <p>Source: {@code BYDAutoPowerDevice.getBatteryRemainPowerEV()}.
     */
    public static Double getBatteryRemainPowerEV(Context ctx) {
        return readDouble(ctx, POWER, "getBatteryRemainPowerEV");
    }

    /**
     * Key-fob battery level (raw int, 0-N where larger = more charge).
     * Live ACC=OFF — answers the "is the user's fob about to die" question
     * proactively rather than waiting for the cluster warning.
     * <p>Source: {@code BYDAutoStatisticDevice.getKeyBatteryLevel()}.
     */
    public static Integer getKeyBatteryLevel(Context ctx) {
        return readInt(ctx, STATISTIC, "getKeyBatteryLevel");
    }

    /**
     * Whether the BYD platform considers the key-fob battery low enough to
     * warrant a cluster warning. Live ACC=OFF.
     */
    public static Integer getKeyDetectionReminder(Context ctx) {
        return readInt(ctx, INSTRUMENT, "getKeyDetectionReminder");
    }

    // ── VIN ───────────────────────────────────────────────────────

    /**
     * Real 17-character VIN (e.g. {@code LGXCH6CD0R2085367}).
     * <p>Use this for cloud auth — {@code getAutoVIN} returns a hashed
     * wrapper string that may not match what BYD's telematics expects.
     * <p>Source: {@code BYDAutoBodyworkDevice.getRealAutoVIN()}.
     */
    public static String getRealAutoVin(Context ctx) {
        return readString(ctx, BODYWORK, "getRealAutoVIN");
    }

    /** The hashed VIN wrapper. Kept for parity in case some flows want it. */
    public static String getAutoVin(Context ctx) {
        return readString(ctx, BODYWORK, "getAutoVIN");
    }

    // ── Diagnostics ───────────────────────────────────────────────

    /**
     * Active fault-code list. Live ACC=OFF.
     * <p>Probed value on test car: {@code []} (no faults).
     * <p>Source: {@code BYDAutoInstrumentDevice.getMalfunctionList()}.
     *
     * @return raw object (typically a {@code java.util.List} or {@code int[]}
     *         depending on trim), or {@code null} if unavailable. Caller
     *         decides how to interpret.
     */
    public static Object getMalfunctionList(Context ctx) {
        Object device = resolveDevice(ctx, INSTRUMENT);
        if (device == null) return null;
        return BydDeviceHelper.callGetter(device, "getMalfunctionList");
    }

    /** Cluster-side dashboard alarm state (separate from anti-theft alarm). */
    public static Integer getDashboardAlarmState(Context ctx) {
        return readInt(ctx, INSTRUMENT, "getDashboardAlarmState");
    }

    // ── Configuration (persistent settings) ───────────────────────

    /** Auto-lock setting active? {@code 0/1}. */
    public static Integer getAutoLock(Context ctx) {
        return readInt(ctx, SETTING, "getAutoLock");
    }

    /** Auto-lock master switch. */
    public static Integer getAutoLockSwitch(Context ctx) {
        return readInt(ctx, SETTING, "getAutoLockSwitch");
    }

    /** Auto-lock delay (minutes). */
    public static Integer getAutoLockTime(Context ctx) {
        return readInt(ctx, SETTING, "getAutoLockTime");
    }

    /** Overspeed lock enabled? */
    public static Integer getOverspeedLock(Context ctx) {
        return readInt(ctx, SETTING, "getOverspeedLock");
    }

    /** Whether remote unlock is currently armed (we set this elsewhere). */
    public static Integer getRemoteCtlUnlockingState(Context ctx) {
        return readInt(ctx, SETTING, "getRemoteCtlUnlockingState");
    }

    /** Lock-rises-windows config. */
    public static Integer getMicroSwitchLockWindowState(Context ctx) {
        return readInt(ctx, SETTING, "getMicroSwitchLockWindowState");
    }

    /** Unlock-drops-windows config. */
    public static Integer getMicroSwitchUnlockWindowState(Context ctx) {
        return readInt(ctx, SETTING, "getMicroSwitchUnlockWindowState");
    }

    // ── Environmental sensors ────────────────────────────────────

    /**
     * Ambient light intensity. Useful as a surveillance ROI gate (e.g. don't
     * record motion in well-lit areas where false positives are common).
     */
    public static Integer getLightIntensity(Context ctx) {
        return readInt(ctx, SENSOR, "getLightIntensity");
    }

    /** Vehicle slope sensor (parked-on-hill detection). */
    public static Integer getSlope(Context ctx) {
        return readInt(ctx, SENSOR, "getSlope");
    }

    /** Outside temperature (°C × 10 typically — divide by 10). */
    public static Integer getOutCarTemperatureRaw(Context ctx) {
        return readInt(ctx, INSTRUMENT, "getOutCarTemperature");
    }

    // ── Internals ────────────────────────────────────────────────

    private static final String BODYWORK   = "android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice";
    private static final String OTA        = "android.hardware.bydauto.ota.BYDAutoOtaDevice";
    private static final String POWER      = "android.hardware.bydauto.power.BYDAutoPowerDevice";
    private static final String SETTING    = "android.hardware.bydauto.setting.BYDAutoSettingDevice";
    private static final String STATISTIC  = "android.hardware.bydauto.statistic.BYDAutoStatisticDevice";
    private static final String INSTRUMENT = "android.hardware.bydauto.instrument.BYDAutoInstrumentDevice";
    private static final String SENSOR     = "android.hardware.bydauto.sensor.BYDAutoSensorDevice";

    /**
     * Drops the device-handle cache. Call when the daemon's appContext is
     * replaced — cached handles were resolved against the old context and
     * may not be valid for the new one.
     */
    public static synchronized void invalidateDeviceCache() {
        deviceCache.clear();
        cachedContext = null;
    }

    private static synchronized Object resolveDevice(Context ctx, String fqn) {
        if (ctx == null) return null;
        if (cachedContext != ctx) {
            // Different context handed in — drop cache so we don't return
            // a device built against a stale context.
            deviceCache.clear();
            cachedContext = ctx;
        }
        Object cached = deviceCache.get(fqn);
        if (cached != null) return cached;
        try {
            Class<?> cls = Class.forName(fqn);
            Method getInstance = cls.getMethod("getInstance", Context.class);
            Object device = getInstance.invoke(null, ctx);
            if (device != null) deviceCache.put(fqn, device);
            return device;
        } catch (Throwable t) {
            logger.debug("resolveDevice(" + fqn + ") failed: " + t.getMessage());
            return null;
        }
    }

    private static Integer readInt(Context ctx, String fqn, String method) {
        Object device = resolveDevice(ctx, fqn);
        if (device == null) return null;
        Object v = BydDeviceHelper.callGetter(device, method);
        if (!(v instanceof Number)) return null;
        int value = ((Number) v).intValue();
        if (value == INVALID_VALUE) return null;
        if (value == COMMAND_FAILED || value == COMMAND_INVALID_VALUE) return null;
        return value;
    }

    private static Double readDouble(Context ctx, String fqn, String method) {
        Object device = resolveDevice(ctx, fqn);
        if (device == null) return null;
        Object v = BydDeviceHelper.callGetter(device, method);
        if (!(v instanceof Number)) return null;
        double value = ((Number) v).doubleValue();
        // Heuristic: most BYD doubles use the same int sentinels cast to double.
        if (value == INVALID_VALUE || value == COMMAND_FAILED || value == COMMAND_INVALID_VALUE) {
            return null;
        }
        return value;
    }

    private static String readString(Context ctx, String fqn, String method) {
        Object device = resolveDevice(ctx, fqn);
        if (device == null) return null;
        Object v = BydDeviceHelper.callGetter(device, method);
        if (!(v instanceof String)) return null;
        String s = (String) v;
        if (s.isEmpty()) return null;
        return s;
    }
}

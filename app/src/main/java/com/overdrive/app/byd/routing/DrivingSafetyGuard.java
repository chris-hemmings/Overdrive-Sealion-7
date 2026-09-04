package com.overdrive.app.byd.routing;

import com.overdrive.app.byd.BydDataCollector;
import com.overdrive.app.config.UnifiedConfigManager;
import com.overdrive.app.monitor.AccMonitor;
import com.overdrive.app.monitor.GearMonitor;
import com.overdrive.app.monitor.GpsMonitor;
import com.overdrive.app.util.DaemonHttpClient;

import android.os.SystemClock;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;

/**
 * Decides whether a motion-sensitive vehicle action is safe to run right now.
 *
 * <p>Split into a pure decision function ({@link #isBlocked}) and a live wrapper
 * ({@link #isMovementBlocked()}), mirroring {@code roadsense.source.VehicleStateGate} —
 * keeping the policy pure means it's unit-testable without Android/HAL dependencies,
 * which matters because mis-gating here means either a false sense of safety (allowed
 * when it shouldn't be) or breaking normal parked remote control (blocked when it
 * shouldn't be).
 *
 * <p>Callers ask only the raw fact ({@code isMovementBlocked(): boolean}) — this class
 * knows nothing about {@link VehicleCommandRouter.VehicleCommand} or risk tiers, so it
 * can gate both the router's dispatch path and the unrelated screen/media endpoints in
 * {@code VehicleControlApiHandler} without either needing to know about the other.
 */
public final class DrivingSafetyGuard {

    private DrivingSafetyGuard() {}

    public static final String GUARD_DOOR_LOCKS = "doorLocks";
    public static final String GUARD_TRUNK = "trunk";
    public static final String GUARD_MIRROR_FOLD = "mirrorFold";
    public static final String GUARD_POSITIONING = "positioning";
    public static final String GUARD_HEADLIGHT_OFF = "headlightOff";
    public static final String GUARD_DISPLAY_BRIGHTNESS = "displayBrightness";
    public static final String GUARD_DISPLAY_POWER = "displayPower";
    public static final String GUARD_SCREEN_MEDIA = "screenMedia";

    private static final String[] GUARD_KEYS = {
            GUARD_DOOR_LOCKS,
            GUARD_TRUNK,
            GUARD_MIRROR_FOLD,
            GUARD_POSITIONING,
            GUARD_HEADLIGHT_OFF,
            GUARD_DISPLAY_BRIGHTNESS,
            GUARD_DISPLAY_POWER,
            GUARD_SCREEN_MEDIA
    };

    /**
     * Gear reads P but speed is still above this, we still treat it as moving
     * ("rolling in Park"). ~0.5 m/s (TripDetector's GPS threshold) converted to
     * km/h with a small margin.
     */
    private static final double PARKED_SPEED_THRESHOLD_KMH = 2.0;

    /** How stale a GearMonitor observation can be before we fail closed. */
    private static final long GEAR_FRESHNESS_MS = 5000L;

    enum GearReading { PARK, NOT_PARK, UNKNOWN }

    public static boolean isKnownGuard(String key) {
        if (key == null) return false;
        for (String candidate : GUARD_KEYS) {
            if (candidate.equals(key)) return true;
        }
        return false;
    }

    /**
     * Per-action policy. Missing, malformed, and unknown settings all keep the
     * guard enabled so an old/corrupt config cannot silently remove protection.
     */
    static boolean isGuardEnabled(JSONObject settings, String key) {
        if (key == null || !isKnownGuard(key)) return true;
        Object value = settings != null ? settings.opt(key) : null;
        return !(value instanceof Boolean) || ((Boolean) value).booleanValue();
    }

    public static boolean isGuardEnabled(String key) {
        try {
            return isGuardEnabled(UnifiedConfigManager.getDrivingSafety(), key);
        } catch (Throwable ignored) {
            return true;
        }
    }

    /** Complete settings snapshot for the API/UI, including default-on values. */
    public static JSONObject getGuardSettings() {
        JSONObject configured = null;
        try {
            configured = UnifiedConfigManager.getDrivingSafety();
        } catch (Throwable ignored) {
        }
        JSONObject resolved = new JSONObject();
        for (String key : GUARD_KEYS) {
            try {
                resolved.put(key, isGuardEnabled(configured, key));
            } catch (Exception ignored) {
            }
        }
        return resolved;
    }

    /** True only when this action's guard is enabled and the live vehicle state blocks it. */
    public static boolean isActionBlocked(String key) {
        return isGuardEnabled(key) && isMovementBlocked();
    }

    /**
     * App-process final-boundary check against the daemon's authoritative vehicle state.
     * Network/config failures fail closed.
     */
    public static boolean isActionBlockedViaDaemon(String key) {
        if (!isKnownGuard(key)) return true;
        HttpURLConnection connection = null;
        try {
            connection = DaemonHttpClient.open(
                    "/api/vehicle/driving-safety/" + key, "GET", 750, 1000);
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) return true;
            StringBuilder body = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) body.append(line);
            }
            JSONObject response = new JSONObject(body.toString());
            return !isDaemonResponseUnblocked(response, key);
        } catch (Throwable ignored) {
            return true;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    static boolean isDaemonResponseUnblocked(JSONObject response, String key) {
        return response != null
                && key != null
                && Boolean.TRUE.equals(response.opt("success"))
                && key.equals(response.opt("guard"))
                && Boolean.FALSE.equals(response.opt("blocked"));
    }

    /**
     * Pure policy — no Android, no singletons, no side effects. Package-visible
     * so tests can exercise it directly.
     *
     * <p>ACC unknown still fails closed. A non-Park gear only blocks when speed
     * itself says we are moving: DiLink 5 often has a stuck D lastEvent while
     * the dashboard already shows P / 0 km/h. Missing speed plus a non-Park
     * gear still fails closed so a moving car with no speed source stays gated.
     */
    static boolean isBlocked(GearReading gear, boolean accOn, boolean accAuthoritative, double speedKmh) {
        if (accAuthoritative && !accOn) return false;  // ACC confidently OFF -> parked -> never blocked
        if (!accAuthoritative) return true;             // genuinely unknown (cold boot) -> fail closed
        boolean moving = !Double.isNaN(speedKmh) && speedKmh > PARKED_SPEED_THRESHOLD_KMH;
        if (moving) return true;
        if (gear == GearReading.NOT_PARK && Double.isNaN(speedKmh)) return true;
        return false;
    }

    /** Live wrapper — reads AccMonitor / GearMonitor / BydDataCollector singletons. */
    public static boolean isMovementBlocked() {
        boolean accAuthoritative = AccMonitor.isAccStateAuthoritative();
        if (!accAuthoritative) return true;
        boolean accOn = AccMonitor.isAccOn();
        if (!accOn) return false;
        return isBlocked(resolveGear(), true, true, resolveSpeedKmh());
    }

    private static GearReading resolveGear() {
        try {
            com.overdrive.app.recording.RecordingModeManager rmm =
                    com.overdrive.app.daemon.CameraDaemon.getRecordingModeManager();
            if (rmm != null) {
                int g = rmm.getCurrentGear();
                if (g == GearMonitor.GEAR_P) return GearReading.PARK;
                if (g >= 2 && g <= GearMonitor.GEAR_S) return GearReading.NOT_PARK;
            }
        } catch (Throwable ignored) {}
        try {
            int carSvc = com.overdrive.app.byd.CarSvcTelemetry.INSTANCE.gearValue();
            if (carSvc == GearMonitor.GEAR_P) return GearReading.PARK;
            if (carSvc >= 2 && carSvc <= GearMonitor.GEAR_S) return GearReading.NOT_PARK;
        } catch (Throwable ignored) {}
        try {
            com.overdrive.app.byd.BydVehicleData vd =
                    BydDataCollector.getInstance().getData();
            if (vd != null) {
                if (vd.gearMode == GearMonitor.GEAR_P) return GearReading.PARK;
                if (vd.gearMode > GearMonitor.GEAR_P
                        && vd.gearMode <= GearMonitor.GEAR_S) {
                    return GearReading.NOT_PARK;
                }
            }
        } catch (Throwable ignored) {}
        GearMonitor gm = GearMonitor.getInstance();
        if (gm == null) return GearReading.UNKNOWN;
        if (!gm.isRunning()) return GearReading.UNKNOWN;
        long age = SystemClock.elapsedRealtime() - gm.getLastUpdateTime();
        if (age < 0 || age >= GEAR_FRESHNESS_MS) return GearReading.UNKNOWN;
        return gm.getCurrentGear() == GearMonitor.GEAR_P
                ? GearReading.PARK : GearReading.NOT_PARK;
    }

    private static double resolveSpeedKmh() {
        try {
            GpsMonitor gps = GpsMonitor.getInstance();
            if (gps != null && gps.hasLocation() && !gps.isLoadedFromCache()) {
                return gps.getSpeed() * 3.6;
            }
        } catch (Throwable ignored) {}
        try {
            int carSvc = com.overdrive.app.byd.CarSvcTelemetry.INSTANCE.resolvedSpeedKmh();
            if (carSvc >= 0 && carSvc <= 300) return carSvc;
        } catch (Throwable ignored) {}
        try {
            com.overdrive.app.byd.BydVehicleData vd =
                    BydDataCollector.getInstance().getData();
            if (vd != null
                    && vd.speedKmh != com.overdrive.app.byd.BydVehicleData.UNAVAILABLE
                    && vd.speedKmh >= 0) {
                return vd.speedKmh;
            }
        } catch (Throwable ignored) {}
        try {
            double live = BydDataCollector.getInstance().readCurrentSpeedKmh();
            if (!Double.isNaN(live)) return live;
        } catch (Throwable ignored) {}
        return Double.NaN;
    }
}

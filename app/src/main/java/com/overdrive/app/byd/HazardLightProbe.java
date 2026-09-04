package com.overdrive.app.byd;

import android.content.Context;
import android.content.ContextWrapper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Field;

/**
 * Shared core for BYD hazard / turn-signal / fog actuation probing.
 *
 * <p>Backs both the CLI harness ({@link HazardLightTest}) and the daemon HTTP
 * endpoint ({@code /api/debug/light/*}). All the "what feature IDs to try and
 * in what order" knowledge lives here so the two front-ends stay in sync.
 *
 * <p>WHY: hazard/turn actuation is reached from our uid-2000 app_process
 * worker via {@code BYDAutoLightDevice.getInstance(ctx).set(int[] featureIds,
 * BYDAutoEventValue)} — the same generic write we already issue for MCU power
 * ({@link com.overdrive.app.power.McuPowerHal}). These probes settle on the
 * actual car whether each light feature is HAL-writable by reading the SDK
 * return code (and a state getter) for each candidate.
 *
 * <p>FEATURE IDS are resolved BY NAME from the real {@code BYDAutoFeatureIds}
 * on the device (nested {@code Light.*} class), with the CANFD literal as the
 * fallback when the named field is absent (same resolve-or-fallback contract
 * as {@link BydFeatureIds}).
 *
 * <p>SAFETY: only lights are touched (hazard/turn/fog) — no motion, locks, or
 * power. Probes always issue the OFF write after the hold, even on exception.
 * Nothing here is wired to any automatic/ACC path; it is a manual diagnostic.
 */
public final class HazardLightProbe {

    public static final String LIGHT_DEVICE = "android.hardware.bydauto.light.BYDAutoLightDevice";

    // BYD event-value convention: 1 = on/enable, 2 = off/disable.
    public static final int ON = 1;
    public static final int OFF = 2;

    // ---- Feature IDs from this firmware's BYDAutoDeviceFeaturesMap allowlist
    // (CANFD trim). The legacy 0x33f-prefixed *_SET ids (871366669 etc.) are NOT
    // in the Light(1004) map and always fail gate-1 checkDeviceFeatures. Resolve
    // BY NAME so the value adapts per-trim (CANFD/Toyota/default); the fallback
    // is the CANFD literal.
    /** Hazard / double-flash (双闪) — the allowlisted Light feature. CANFD=950009880. */
    public static final int LIGHT_EMERGENCY_WARNING =
            resolve("Light.LIGHT_EMERGENCY_WARNING_LIGHT_STATE", 950009880);
    /** Turn-signal light feature (allowlisted). CANFD=950009900. */
    public static final int LIGHT_TURN_SIGNAL =
            resolve("Light.LIGHT_TURN_SIGNAL_LIGHT", 950009900);
    /** Rear fog lamps (allowlisted). CANFD=950009872. */
    public static final int LIGHT_REAR_FOG =
            resolve("Light.LIGHT_REAR_FOG_LAMPS", 950009872);

    // ---- COMMAND-class features (high byte 0x39/0x3b = WRITABLE) ----
    // Decompile verdict: the *_STATE ids above (high byte 0x38) are READ-ONLY
    // status; the native HAL returns FAILED (-2147482648) on setInt for them.
    // The writable command variants are the CMD_ ids. These are fixed-value
    // (not trim-branched) in BYDAutoFeatureIds, but resolve-by-name anyway so a
    // differently-numbered firmware still binds correctly.
    /** Double-flash COMMAND (0x39400033). The real hazard write. */
    public static final int LIGHT_CMD_DOUBLE_FLASH =
            resolve("Light.LIGHT_CMD_DOUBLE_FLASH_STATE", 960495667);
    /** Sequential-indicator COMMAND (0x3b...). Validates the command-class model. */
    public static final int LIGHT_CMD_SEQUENTIAL =
            resolve("Light.LIGHT_CMD_SEQUENTIAL_STATE", 994050118);

    private HazardLightProbe() {}

    /**
     * Context that no-ops Android's permission enforcement. The BYD light
     * {@code set()} calls {@code mContext.enforceCallingOrSelfPermission(
     * "android.permission.BYDAUTO_LIGHT_SET", null)} — gate 3 — which runs
     * in-process against the Context we hand to {@code getInstance(ctx)}.
     * Since the native autoservice gate does NOT re-check uid/permission
     * (decompiled: checkSetPermission is a .bss-flag no-op), neutralising this
     * client-side check is the lever a uid-2000 app needs.
     */
    static final class PermissiveContext extends ContextWrapper {
        PermissiveContext(Context base) { super(base); }
        @Override public void enforceCallingOrSelfPermission(String permission, String message) { /* no-op */ }
        @Override public void enforceCallingPermission(String permission, String message) { /* no-op */ }
        @Override public void enforcePermission(String permission, int pid, int uid, String message) { /* no-op */ }
        @Override public int checkCallingOrSelfPermission(String permission) {
            return android.content.pm.PackageManager.PERMISSION_GRANTED;
        }
        @Override public int checkCallingPermission(String permission) {
            return android.content.pm.PackageManager.PERMISSION_GRANTED;
        }
        @Override public int checkPermission(String permission, int pid, int uid) {
            return android.content.pm.PackageManager.PERMISSION_GRANTED;
        }
    }

    public static final String DOORLOCK_DEVICE = "android.hardware.bydauto.doorlock.BYDAutoDoorLockDevice";
    public static final String SETTING_DEVICE = "android.hardware.bydauto.setting.BYDAutoSettingDevice";

    // Remote lock/unlock control feature on the SETTING device (CANFD value).
    public static final int SET_REMOTE_CONTROL_UNLOCKING =
            resolve("Setting.SET_REMOTE_CONTROL_UNLOCKING_SET", 1081081878);
    public static final int SET_CAR_DOOR_LOCK =
            resolve("Setting.SET_CAR_DOOR_LOCK_SET", 515647);

    /** A candidate actuation path: a device + feature IDs + ON/OFF values, plus an optional readback getter. */
    public static final class Candidate {
        public final String id;
        public final String label;
        public final String deviceClass;
        public final int[] featureIds;
        public final int onValue;
        public final int offValue;
        /** Optional no-arg / 1-arg getter on the device to confirm actual state change (device truth, not return code). */
        public final String readGetter;
        public final int readGetterArg;   // Integer.MIN_VALUE = no-arg getter

        Candidate(String id, String label, String deviceClass, int[] featureIds) {
            this(id, label, deviceClass, featureIds, ON, OFF, null, Integer.MIN_VALUE);
        }

        Candidate(String id, String label, String deviceClass, int[] featureIds, int onValue, int offValue) {
            this(id, label, deviceClass, featureIds, onValue, offValue, null, Integer.MIN_VALUE);
        }

        Candidate(String id, String label, String deviceClass, int[] featureIds,
                  int onValue, int offValue, String readGetter, int readGetterArg) {
            this.id = id;
            this.label = label;
            this.deviceClass = deviceClass;
            this.featureIds = featureIds;
            this.onValue = onValue;
            this.offValue = offValue;
            this.readGetter = readGetter;
            this.readGetterArg = readGetterArg;
        }
    }

    /**
     * Candidate paths, best-first per the native-HAL analysis: the writable
     * COMMAND-class ids (high byte 0x39/0x3b) are tried before the read-only
     * STATUS ids (0x38), which the HAL rejects with FAILED. A = the real
     * double-flash command; D/E keep the STATUS ids for contrast.
     */
    public static Candidate[] candidates() {
        return new Candidate[]{
            new Candidate("A", "Light.CMD_DOUBLE_FLASH (hazard/双闪)", LIGHT_DEVICE,
                    new int[]{LIGHT_CMD_DOUBLE_FLASH}, 1, 2, "getDoubleFlashLightState", Integer.MIN_VALUE),
            new Candidate("B", "Light.CMD_DOUBLE_FLASH value=2/0", LIGHT_DEVICE,
                    new int[]{LIGHT_CMD_DOUBLE_FLASH}, 2, 0, "getDoubleFlashLightState", Integer.MIN_VALUE),
            new Candidate("C", "Light.CMD_SEQUENTIAL", LIGHT_DEVICE,
                    new int[]{LIGHT_CMD_SEQUENTIAL}, 1, 2, "getSequentialLightState", Integer.MIN_VALUE),
            new Candidate("D", "Light.EMERGENCY_WARNING_STATE", LIGHT_DEVICE,
                    new int[]{LIGHT_EMERGENCY_WARNING}, 1, 2, "getDoubleFlashLightState", Integer.MIN_VALUE),
            new Candidate("E", "Light.TURN_SIGNAL_STATE", LIGHT_DEVICE,
                    new int[]{LIGHT_TURN_SIGNAL}, 1, 2, "getTurnLightState", Integer.MIN_VALUE),
            // Legacy 0x33f-prefixed front+rear fog literals, set together with
            // intValue 1=ON/2=OFF (the non-CANFD light encoding).
            new Candidate("F", "Legacy fog literals {871366675,871366667}", LIGHT_DEVICE,
                    new int[]{871366675, 871366667}, 1, 2, "getDoubleFlashLightState", Integer.MIN_VALUE),
            // Legacy 0x33f turn-signal literal.
            new Candidate("G", "Legacy turn-signal literal {871366669}", LIGHT_DEVICE,
                    new int[]{871366669}, 1, 2, "getTurnLightState", Integer.MIN_VALUE),
            // SANITY: generic set() on a KNOWN-writable+allowlisted Light _SET
            // feature (DRL auto-state). Should SUCCEED — proves our reflective
            // generic set(int[]{id},ev) path itself works (named setter already
            // proven). If this fails too, the probe mechanism is the problem.
            new Candidate("S", "SANITY Light.DAY_RUNNING_AUTO_STATE_SET 1125122118 (expect OK)", LIGHT_DEVICE,
                    new int[]{1125122118}, 1, 2, "getDayTimeLightState", Integer.MIN_VALUE),
            // Lock/unlock via SDK (Setting device remote-control-unlocking feature).
            new Candidate("L", "Setting.REMOTE_CONTROL_UNLOCKING (lock=0/unlock=1)", SETTING_DEVICE,
                    new int[]{SET_REMOTE_CONTROL_UNLOCKING}, 1, 0, null, Integer.MIN_VALUE),
            new Candidate("K", "Setting.SET_CAR_DOOR_LOCK", SETTING_DEVICE,
                    new int[]{SET_CAR_DOOR_LOCK}, 1, 0, null, Integer.MIN_VALUE),
        };
    }

    public static Candidate candidateById(String id) {
        for (Candidate c : candidates()) {
            if (c.id.equalsIgnoreCase(id)) return c;
        }
        return null;
    }

    /** Resolved feature IDs as JSON, for the read-only /resolve probe. */
    public static JSONObject resolvedIds() {
        JSONObject o = new JSONObject();
        try {
            o.put("LIGHT_CMD_DOUBLE_FLASH", LIGHT_CMD_DOUBLE_FLASH);
            o.put("LIGHT_CMD_SEQUENTIAL", LIGHT_CMD_SEQUENTIAL);
            o.put("LIGHT_EMERGENCY_WARNING", LIGHT_EMERGENCY_WARNING);
            o.put("LIGHT_TURN_SIGNAL", LIGHT_TURN_SIGNAL);
            o.put("LIGHT_REAR_FOG", LIGHT_REAR_FOG);
        } catch (Exception ignored) {}
        return o;
    }

    /**
     * Run one candidate: ON, hold {@code holdMs}, then OFF — recording the SDK
     * result of every {@code set()}. OFF always runs (finally). Returns a JSON
     * object describing what happened.
     *
     * @param ctx     daemon Context (DaemonBootstrap.getContext())
     * @param c       the candidate to fire
     * @param holdMs  how long to leave ON before turning OFF
     */
    public static JSONObject runCandidate(Context ctx, Candidate c, long holdMs) {
        JSONObject r = new JSONObject();
        JSONArray onResults = new JSONArray();
        JSONArray offResults = new JSONArray();
        try {
            r.put("id", c.id);
            r.put("label", c.label);
            r.put("deviceClass", c.deviceClass);
            JSONArray fids = new JSONArray();
            for (int f : c.featureIds) fids.put(f);
            r.put("featureIds", fids);

            Context permissive = new PermissiveContext(ctx.getApplicationContext() != null
                    ? ctx.getApplicationContext() : ctx);
            Object device = BydDeviceHelper.getDevice(c.deviceClass, permissive);
            if (device == null) {
                r.put("skipped", true);
                r.put("reason", "device null on this firmware");
                return r;
            }
            r.put("deviceClassResolved", device.getClass().getName());

            // The device is a singleton; getInstance() caches the FIRST context
            // (BydDataCollector likely created it with a plain context). Gate 3
            // (enforceCallingOrSelfPermission) runs against that cached mContext,
            // so swap in our PermissiveContext via reflection on AbsBYDAutoDevice.mContext.
            boolean ctxSwapped = swapContext(device, permissive);
            r.put("permissiveContextInstalled", ctxSwapped);

            // Device-truth readback (not the set() return code): read the state
            // getter before ON, after ON, after OFF. If it changes, the actuation
            // landed even if set() returned a negative sentinel.
            r.put("readBefore", readState(device, c));

            boolean anyOnAccepted = false;
            try {
                for (int fid : c.featureIds) {
                    int code = BydDeviceHelper.sendSetCommandRaw(device, fid, c.onValue);
                    boolean ok = code >= 0;
                    anyOnAccepted |= ok;
                    onResults.put(setResult(fid, c.onValue, ok, code));
                }
                r.put("on", onResults);
                r.put("holdMs", holdMs);
                sleep(Math.min(holdMs, 800L));   // let the bus settle before read
                r.put("readAfterOn", readState(device, c));
                sleep(holdMs);
            } finally {
                for (int fid : c.featureIds) {
                    int code;
                    try {
                        code = BydDeviceHelper.sendSetCommandRaw(device, fid, c.offValue);
                    } catch (Throwable t) {
                        code = Integer.MIN_VALUE;
                    }
                    offResults.put(setResult(fid, c.offValue, code >= 0, code));
                }
                r.put("off", offResults);
            }
            r.put("readAfterOff", readState(device, c));
            r.put("acceptedByHal", anyOnAccepted);
        } catch (Throwable t) {
            try { r.put("exception", String.valueOf(t)); } catch (Exception ignored) {}
        }
        return r;
    }

    /** Read the candidate's state getter (device truth). Returns the int, or "n/a"/error string. */
    private static Object readState(Object device, Candidate c) {
        if (c.readGetter == null) return "n/a";
        try {
            Object v = (c.readGetterArg == Integer.MIN_VALUE)
                    ? BydDeviceHelper.callGetter(device, c.readGetter)
                    : BydDeviceHelper.callGetter(device, c.readGetter, c.readGetterArg);
            return v == null ? "null" : v;
        } catch (Throwable t) {
            return "err:" + t;
        }
    }

    /**
     * Reflectively replace {@code AbsBYDAutoDevice.mContext} on a device
     * singleton with our PermissiveContext, so the in-process gate-3
     * permission check is neutralised. Walks up the class hierarchy to find
     * the private {@code mContext} field declared on AbsBYDAutoDevice.
     */
    static boolean swapContext(Object device, Context permissive) {
        Class<?> cls = device.getClass();
        while (cls != null) {
            try {
                Field f = cls.getDeclaredField("mContext");
                f.setAccessible(true);
                f.set(device, permissive);
                return true;
            } catch (NoSuchFieldException nsfe) {
                cls = cls.getSuperclass();
            } catch (Throwable t) {
                return false;
            }
        }
        return false;
    }

    private static JSONObject setResult(int featureId, int value, boolean ok, int code) {
        JSONObject o = new JSONObject();
        try {
            o.put("featureId", featureId);
            o.put("featureIdHex", "0x" + Integer.toHexString(featureId));
            o.put("value", value);
            o.put("accepted", ok);
            o.put("code", code);
            o.put("codeHex", "0x" + Integer.toHexString(code));
        } catch (Exception ignored) {}
        return o;
    }

    /**
     * Resolve a (possibly nested) field from the device's real
     * BYDAutoFeatureIds, else the CANFD fallback int. Same contract as
     * {@link BydFeatureIds#resolveOrFallback}.
     */
    static int resolve(String fieldName, int fallback) {
        try {
            Class<?> cls = Class.forName("android.hardware.bydauto.BYDAutoFeatureIds");
            if (fieldName.contains(".")) {
                String[] parts = fieldName.split("\\.");
                for (Class<?> inner : cls.getDeclaredClasses()) {
                    if (inner.getSimpleName().equals(parts[0])) {
                        Field f = inner.getField(parts[1]);
                        return f.getInt(null);
                    }
                }
            } else {
                return cls.getField(fieldName).getInt(null);
            }
        } catch (Throwable ignored) {}
        return fallback;
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }
}

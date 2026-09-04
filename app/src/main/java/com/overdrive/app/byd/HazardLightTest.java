package com.overdrive.app.byd;

import android.content.Context;

import com.overdrive.app.daemon.DaemonBootstrap;

import org.json.JSONObject;

import java.lang.reflect.Method;

/**
 * STANDALONE, ON-CAR CLI harness for BYD hazard / turn-signal actuation.
 *
 * <p>Thin wrapper over {@link HazardLightProbe} (which holds the decoded
 * feature IDs and candidate logic, shared with the daemon HTTP endpoint
 * {@code /api/debug/light/*}). Use this when you want to test from a raw ADB
 * shell without the daemon's HTTP server up.
 *
 * <p>WHY: hazard/turn actuation is reached from our uid-2000 app_process
 * worker via {@code BYDAutoLightDevice.getInstance(ctx).set(int[], BYDAutoEventValue)}
 * — the same generic write we already issue for MCU power. This settles on the
 * actual car whether each light feature is HAL-writable by reading each
 * candidate's SDK return code.
 *
 * <p>RUN (from an ADB shell, which is uid 2000 — same as the daemon):
 * <pre>
 *   CLASSPATH=$(pm path com.overdrive.app | sed 's/package://') \
 *     app_process /system/bin com.overdrive.app.byd.HazardLightTest [seconds]
 * </pre>
 * Optional arg = seconds to hold each candidate ON before OFF (default 4).
 * Watch the car; "acceptedByHal":true / per-set "accepted":true means the HAL
 * took the write. If A/B/C all reject from uid 2000, the gap is a HAL
 * uid/package gate rather than just-unimplemented — capture the JSON.
 *
 * <p>SAFETY: light-only (hazard/turn/fog); OFF always issued in a finally
 * block by the probe. Not wired to any automatic/ACC path.
 */
public final class HazardLightTest {

    private HazardLightTest() {}

    public static void main(String[] args) {
        long holdMs = 4000L;
        if (args != null && args.length > 0) {
            try { holdMs = Long.parseLong(args[0].trim()) * 1000L; } catch (NumberFormatException ignored) {}
        }

        out("=== HazardLightTest start (uid=" + android.os.Process.myUid() + ") ===");

        Context ctx = DaemonBootstrap.init();
        if (ctx == null) {
            out("FATAL: DaemonBootstrap.init() returned null — cannot get a Context.");
            return;
        }
        out("Context OK: " + safePkg(ctx));
        out("Resolved feature IDs: " + HazardLightProbe.resolvedIds());
        out("");

        for (HazardLightProbe.Candidate c : HazardLightProbe.candidates()) {
            out("---- candidate " + c.id + ": " + c.label + " ----");
            out("  WATCH THE CAR — holding " + (holdMs / 1000.0) + "s ON");
            JSONObject r = HazardLightProbe.runCandidate(ctx, c, holdMs);
            out("  " + r.toString());
            out("");
        }

        out("=== HazardLightTest done. acceptedByHal / set accepted=true => HAL took the write. ===");
        out("If A/B/C all rejected from uid 2000, the gap is a HAL uid/package gate, not just unimplemented.");
    }

    private static String safePkg(Context ctx) {
        try { return ctx.getPackageName(); } catch (Throwable t) { return "(no package)"; }
    }

    private static void out(String s) {
        System.out.println(s);
        try {
            Method i = Class.forName("android.util.Log").getMethod("i", String.class, String.class);
            i.invoke(null, "HazardLightTest", s);
        } catch (Throwable ignored) {}
    }
}

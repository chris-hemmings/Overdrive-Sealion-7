package com.overdrive.app.daemon;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/**
 * Pins the DiLink 5 ACC-on path so parked keep-alive cannot slam the
 * brightness slider after the driver turns accessory power on.
 */
public class AccSentryDiLink5AccOnPanelContractTest {

    @Test
    public void dilink5AccOnLeavesSentryAndDoesNotWriteBrightness()
            throws IOException {
        String sentry = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/daemon/AccSentryDaemon.java");
        String monitor = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/monitor/AccMonitor.java");

        int handle = sentry.indexOf("private static void handlePowerLevelChanged");
        int handleEnd = sentry.indexOf(
                "private static class AccListener", handle);
        assertTrue(handle >= 0);
        assertTrue(handleEnd > handle);
        String handleBody = sentry.substring(handle, handleEnd);
        assertTrue(handleBody.contains("isDilink5Unit()"));
        assertTrue(handleBody.contains("leaving sentry"));
        assertTrue(handleBody.contains(
                "treating as ACC OFF"));

        int dilink5Fallback = sentry.indexOf(
                "if (isDilink5Unit()) {");
        int brightnessWrite = sentry.indexOf(
                "settings put system screen_brightness \" + brightness");
        assertTrue(dilink5Fallback >= 0);
        assertTrue(brightnessWrite > dilink5Fallback);
        assertTrue(sentry.contains("!dilink4 && !isDilink5Unit()"));
        assertTrue(sentry.contains("classifyBodyworkPowerLevel"));
        assertTrue(sentry.contains("readDiLink5PowerLevel"));
        assertTrue(sentry.contains("CarSvcTelemetry.INSTANCE.dumpsysText()"));

        int heartbeat = sentry.indexOf(
                "private static void applyHeartbeatPowerLevel");
        int heartbeatEnd = sentry.indexOf(
                "private static synchronized void startAccStateHeartbeat",
                heartbeat);
        assertTrue(heartbeat >= 0);
        assertTrue(heartbeatEnd > heartbeat);
        String heartbeatBody = sentry.substring(heartbeat, heartbeatEnd);
        assertTrue(heartbeatBody.contains("level == POWER_LEVEL_ACC && !isDilink5Unit()"));
        assertTrue(heartbeatBody.contains("level == POWER_LEVEL_OFF"));

        assertTrue(monitor.contains("DiLink5PowerMode.classifyCurrentLine"));
        assertTrue(monitor.contains("classifyDiLink5PowerModeCurrentLine"));
        assertTrue(monitor.contains("CarSvcTelemetry.INSTANCE.dumpsysText()"));
        assertFalse(monitor.contains("grep 'current' | head -1"));
        assertFalse(monitor.contains("Vehicle is LOCKED"));
        assertFalse(monitor.contains("isInteractive=false"));
        assertFalse(monitor.contains("dumpsys car_service 2>/dev/null | grep"));
    }

    private static String readRepositoryFile(String relativePath)
            throws IOException {
        Path current = Paths.get(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
        while (current != null) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return new String(
                        Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
            Path fromModule = current.resolve(
                    relativePath.replaceFirst("^app/", ""));
            if (Files.isRegularFile(fromModule)) {
                return new String(
                        Files.readAllBytes(fromModule), StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new AssertionError(
                "Could not locate repository file: " + relativePath);
    }
}

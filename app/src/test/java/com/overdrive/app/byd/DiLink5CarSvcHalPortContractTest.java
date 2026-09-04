package com.overdrive.app.byd;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/**
 * Pins DiLink 5 live readers to car_service so a stuck-valid Park from the
 * vendor gearbox/speed HAL cannot freeze trips, overlay, or ACC heartbeat.
 */
public class DiLink5CarSvcHalPortContractTest {

    @Test
    public void gearMonitorPrefersCarSvcBeforeAdapterAndHal() throws IOException {
        String gear = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/monitor/GearMonitor.java");
        int carSvc = gear.indexOf("readFromCarSvc()");
        int adapter = gear.indexOf("readFromCarAdapter()");
        int hal = gear.indexOf("getGearMethod.invoke");
        assertTrue(carSvc >= 0);
        assertTrue(adapter > carSvc);
        assertTrue(gear.contains("CarSvcTelemetry.INSTANCE.gearValue()"));
        assertTrue(hal > carSvc);
    }

    @Test
    public void liveSpeedAndGearGettersUseCarSvc() throws IOException {
        String collector = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/byd/BydDataCollector.java");
        int speedFn = collector.indexOf("public double readCurrentSpeedKmh()");
        int gearFn = collector.indexOf("public int readGearNow()");
        assertTrue(speedFn >= 0);
        assertTrue(gearFn >= 0);
        assertTrue(collector.substring(speedFn, speedFn + 800)
                .contains("CarSvcTelemetry.INSTANCE.resolvedSpeedKmh()"));
        assertTrue(collector.substring(gearFn, gearFn + 800)
                .contains("CarSvcTelemetry.INSTANCE.gearValue()"));
        assertTrue(collector.contains("int carSvcSpeed = CarSvcTelemetry.INSTANCE.resolvedSpeedKmh()"));
        assertTrue(collector.contains("int carSvcGear = CarSvcTelemetry.INSTANCE.gearValue()"));
    }

    @Test
    public void tripsAndOverlayUseCarSvcGearAndTurnStalks() throws IOException {
        String trips = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/trips/TripAnalyticsManager.java");
        String samples = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/trips/TripTelemetryRecorder.java");
        String overlay = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/telemetry/TelemetryDataCollector.java");
        assertTrue(trips.contains("resolveLiveGear"));
        assertTrue(trips.contains("CarSvcTelemetry.INSTANCE.gearValue()"));
        assertTrue(trips.contains("detector.startMotionWatch()"));
        assertTrue(samples.contains("CarSvcTelemetry.INSTANCE.gearValue()"));
        int snapshot = overlay.indexOf("boolean turnFromSnapshot = false");
        int hal = overlay.indexOf("getTurnLightFlashStateMethod.invoke");
        assertTrue(snapshot >= 0);
        assertTrue(hal > snapshot);
        assertTrue(overlay.contains("!turnFromSnapshot"));
    }

    @Test
    public void tripDetectorStartsAndStopsFromFusedGpsAndCanSpeed() throws IOException {
        String detector = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/trips/TripDetector.java");
        assertTrue(detector.contains("startMotionWatch"));
        assertTrue(detector.contains("TripMotionGate.mayStartFromMotion"));
        assertTrue(detector.contains("TripMotionGate.mayStopFromMotion"));
        assertTrue(detector.contains("CarSvcTelemetry.INSTANCE.resolvedSpeedKmh()"));
        assertTrue(detector.contains("readFusedSpeedKmh"));
        assertTrue(detector.contains("gpsSpeedUsable"));
    }

    @Test
    public void dilink5CarServiceDumpIsSharedNotPerCallerShell() throws IOException {
        String telemetry = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/byd/CarSvcTelemetry.kt");
        String monitor = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/monitor/AccMonitor.java");
        String collector = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/byd/BydDataCollector.java");
        String gear = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/monitor/GearMonitor.java");
        String accCtl = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/daemon/sentry/AccMonitorController.kt");
        String sentry = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/daemon/AccSentryDaemon.java");
        assertTrue(telemetry.contains("synchronized(dumpLock)"));
        assertTrue(telemetry.contains("DUMP_TTL_MS = 2000L"));
        assertTrue(monitor.contains("CarSvcTelemetry.INSTANCE.dumpsysText()"));
        assertFalse(monitor.contains("dumpsys car_service 2>/dev/null | grep"));
        assertTrue(collector.contains("CarSvcTelemetry.INSTANCE.dumpsysText()"));
        assertFalse(collector.contains("grep -E '0x21403407|0x2140461c'"));
        assertTrue(gear.contains("CarSvcTelemetry.INSTANCE.dumpsysText()"));
        assertTrue(gear.contains("parseGearFromText"));
        int dilinkDump = gear.indexOf("if (com.overdrive.app.byd.DiLink5Platform.isActive()) {");
        int dilinkDumpFallback = gear.indexOf("String propDump = com.overdrive.app.monitor.AccMonitor.execShell(", dilinkDump);
        assertTrue(dilinkDump >= 0 && dilinkDumpFallback > dilinkDump);
        assertFalse(gear.substring(dilinkDump, dilinkDumpFallback)
                .contains("int32Values: [4]"));
        assertTrue(accCtl.contains("pollDiLink5Acc"));
        assertTrue(accCtl.contains("CarSvcTelemetry.dumpsysText()"));
        assertTrue(accCtl.contains("DiLink5PowerMode.classifyCurrentLine"));
        assertTrue(sentry.contains("CarSvcTelemetry.INSTANCE.dumpsysText()"));
        int readDi = sentry.indexOf("private static int readDiLink5PowerLevel");
        int readDiEnd = sentry.indexOf("private static void applyHeartbeatPowerLevel", readDi);
        assertTrue(readDi >= 0 && readDiEnd > readDi);
        assertFalse(sentry.substring(readDi, readDiEnd)
                .contains("dumpsys car_service 2>/dev/null"));
    }

    @Test
    public void chargingPluggedUsesGunLatchAndGatesSessionFeed() throws IOException {
        String telemetry = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/byd/CarSvcTelemetry.kt");
        String collector = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/byd/BydDataCollector.java");
        assertTrue(telemetry.contains("PROP_CHARGING_GUN_STATE = 0x21403407"));
        assertTrue(telemetry.contains("fun gunConnected()"));
        int apply = telemetry.indexOf("fun applyChargingOverrides");
        int applyEnd = telemetry.indexOf("private fun feedSessionManager", apply);
        assertTrue(apply >= 0 && applyEnd > apply);
        String body = telemetry.substring(apply, applyEnd);
        assertTrue(body.contains("val rawCharging = (state == CHARGE_STATE_ACTIVE)"));
        assertTrue(body.contains("gunConnected()"));
        assertTrue(body.contains("pluggedBase = (gun == 1)"));
        assertTrue(body.contains("sessionAdmit.admit"));
        assertTrue(body.contains("pluggedBase || charging"));
        assertFalse(body.contains("pluggedBase = (state == 2 || state == 3 || state == 4)"));
        assertFalse(body.contains("rawCharging && notDriving && pluggedBase"));
        int overlay = collector.indexOf("private void applyCarSvcOverlay");
        int overlayEnd = collector.indexOf("public synchronized void collectAll()", overlay);
        assertTrue(overlay >= 0 && overlayEnd > overlay);
        String overlayBody = collector.substring(overlay, overlayEnd);
        assertTrue(overlayBody.contains("gunConnected()"));
        assertTrue(overlayBody.contains("mapCarSvcGunToHal"));
        assertTrue(overlayBody.contains("socPercentValue()"));
        assertTrue(overlayBody.contains("totalMileageKm()"));
        assertTrue(telemetry.contains("STATISTIC_TOTAL_MILEAGE"));
        assertTrue(telemetry.contains("odometerKm"));
    }

    @Test
    public void dilink5SocPrefersRemainingBatteryPowerThenCache() throws IOException {
        String telemetry = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/byd/CarSvcTelemetry.kt");
        String monitor = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/monitor/VehicleDataMonitor.java");
        int parse = telemetry.indexOf("fun parseSocFromText");
        int parseEnd = telemetry.indexOf("fun socPercentValue", parse);
        assertTrue(parse >= 0 && parseEnd > parse);
        String parseBody = telemetry.substring(parse, parseEnd);
        int remain = parseBody.indexOf("PROP_REMAINING_BATTERY_POWER");
        int named = parseBody.indexOf("PROP_SOC_VALUER");
        assertTrue(remain >= 0 && named > remain);
        assertTrue(telemetry.contains("PROP_REMAINING_BATTERY_POWER = 0x21604420"));
        assertTrue(telemetry.contains("persistSoc(live)"));
        int value = telemetry.indexOf("fun socPercentValue");
        int valueEnd = telemetry.indexOf("fun socPercent()", value);
        assertTrue(value >= 0 && valueEnd > value);
        String valueBody = telemetry.substring(value, valueEnd);
        int live = valueBody.indexOf("parseSocFromText");
        int disk = valueBody.indexOf("readPersistedSoc");
        int hist = valueBody.indexOf("readHistorySoc");
        assertTrue(live >= 0 && disk > live && hist > disk);
        assertTrue(monitor.contains("CarSvcTelemetry.INSTANCE.socPercentValue()"));
        int getSoc = monitor.indexOf("public BatterySocData getBatterySoc()");
        int getSocEnd = monitor.indexOf("public boolean isPhev()", getSoc);
        assertTrue(getSoc >= 0 && getSocEnd > getSoc);
        String getBody = monitor.substring(getSoc, getSocEnd);
        assertTrue(getBody.indexOf("DiLink5Platform.isActive()")
                < getBody.indexOf("getVd()"));
    }

    @Test
    public void dilink5ScreenDeterrentWakesViaCarPowerHome() throws IOException {
        String deterrent = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/surveillance/ScreenDeterrent.java");
        int wake = deterrent.indexOf("private static void wakePanel");
        int wakeEnd = deterrent.indexOf("private static void turnBacklightOff", wake);
        assertTrue(wake >= 0 && wakeEnd > wake);
        String wakeBody = deterrent.substring(wake, wakeEnd);
        assertTrue(wakeBody.contains("DiLink5Platform.isActive()"));
        assertTrue(wakeBody.contains("dispatchCarPowerBacklight(\"on\")"));
        int off = deterrent.indexOf("private static void turnBacklightOff");
        int offEnd = deterrent.indexOf("private static Point resolveDisplaySize", off);
        if (offEnd < 0) offEnd = deterrent.indexOf("// ── Activity launch", off);
        assertTrue(off >= 0 && offEnd > off);
        String offBody = deterrent.substring(off, offEnd);
        assertTrue(offBody.contains("dispatchCarPowerBacklight(\"off\")"));
        String engine = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/surveillance/SurveillanceEngineGpu.java");
        assertTrue(engine.contains("SentryScreenWalkLog.consumeNewApproach(maxThreat)"));
        assertTrue(engine.contains("if (wake) sd.onMotionDetected()"));
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

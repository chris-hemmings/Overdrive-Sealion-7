package com.overdrive.app.overlay;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

public class OverlayPermissionAssetContractTest {

    @Test
    public void nativePermissionSurfacesUseSharedAuthoritativeChecker()
            throws Exception {
        assertUsesChecker(
                "src/main/java/com/overdrive/app/overlay/SetupGuideDialog.java");
        assertUsesChecker(
                "src/main/java/com/overdrive/app/ui/fragment/settings/"
                        + "RemoteCommunicationSettingsBinder.kt");
        assertUsesChecker(
                "src/main/java/com/overdrive/app/overlay/StatusOverlayService.java");
        assertUsesChecker(
                "src/main/java/com/overdrive/app/overlay/MessageOverlayService.java");
        assertUsesChecker(
                "src/main/java/com/overdrive/app/services/RemoteVoiceService.java");
        assertUsesChecker(
                "src/main/java/com/overdrive/app/roadsense/overlay/"
                        + "RoadSenseOverlayService.kt");
    }

    @Test
    public void setupDialogRefreshesPermissionAfterReturningFromSettings()
            throws Exception {
        String source = read(
                "src/main/java/com/overdrive/app/overlay/SetupGuideDialog.java");
        assertTrue(source.contains("addOnWindowFocusChangeListener"));
        assertTrue(source.contains(
                "renderOverlayPermission(context, btnOverlay, stepOverlayCheck)"));
    }

    @Test
    public void statusPollChecksPermissionOnlyBeforeAttachingNewWindows()
            throws Exception {
        String source = read(
                "src/main/java/com/overdrive/app/overlay/StatusOverlayService.java");
        assertTrue(source.contains("if (visible && !camCloseAttached"));
        assertTrue(source.contains("if (visible && !bsCloseAttached"));
    }

    @Test
    public void shellDaemonSetsOverlayAppOpsUnconditionally() throws Exception {
        // OverlayPermissionChecker (and Settings.canDrawOverlays) consult
        // AppOps. dumpsys reports SYSTEM_ALERT_WINDOW granted=true as an
        // install permission even when AppOps is still denied, so the grant
        // must be an unconditional `appops set` from the shell-UID daemon —
        // before the skippable pm grant loop, not inside it.
        String source = read(
                "src/main/java/com/overdrive/app/daemon/PermissionGranter.java");
        int appOpCall = source.indexOf("grantOverlayAppOp(packageName)");
        int pmLoop = source.indexOf("for (String permission : ALL_PERMISSIONS)");
        assertTrue("PermissionGranter must appops-set overlay permission",
                appOpCall >= 0);
        assertTrue("overlay AppOps must run before the skippable pm grant loop",
                appOpCall < pmLoop);
        assertTrue(source.contains(
                "appops set \" + packageName + \" SYSTEM_ALERT_WINDOW allow"));
    }

    private static void assertUsesChecker(String relativePath) throws Exception {
        String source = read(relativePath);
        assertTrue(relativePath, source.contains("OverlayPermissionChecker.isGranted"));
        assertFalse(relativePath, source.contains("Settings.canDrawOverlays("));
    }

    private static String read(String relativePath) throws Exception {
        Path direct = Paths.get(relativePath);
        Path path = Files.exists(direct)
                ? direct
                : Paths.get("app").resolve(relativePath);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}

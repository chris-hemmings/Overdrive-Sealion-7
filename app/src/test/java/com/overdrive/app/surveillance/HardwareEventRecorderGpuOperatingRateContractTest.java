package com.overdrive.app.surveillance;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/**
 * Pins the primary-encoder operating-rate flag and the 150 ms stall probes
 * so freeze-and-skip cannot silently regress to an unpinned Venus + no
 * per-gap logs (the combination that left Sealion 7 cam_* stts undiagnosed).
 */
public class HardwareEventRecorderGpuOperatingRateContractTest {

    @Test
    public void primaryEncoderPinsOperatingRateAndLogsInterframeStalls()
            throws IOException {
        String recorder = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/surveillance/HardwareEventRecorderGpu.java");
        assertTrue(recorder.contains("private static final boolean PIN_OPERATING_RATE = true"));
        assertFalse(recorder.contains("private static final boolean PIN_OPERATING_RATE = false"));
        assertTrue(recorder.contains("private static final long STALL_LOG_GAP_US = 150_000L"));
        assertTrue(recorder.contains("maybeLogInterframeStall"));
        assertTrue(recorder.contains("public String stallSnapshot()"));
    }

    @Test
    public void secondaryEncodersStillForceThePinOff() throws IOException {
        String oem = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/camera/OemDashcamPipeline.java");
        String pipeline = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/surveillance/GpuSurveillancePipeline.java");
        assertTrue(oem.contains("encoder.setPinOperatingRate(false)"));
        assertTrue(pipeline.contains("streamEncoder.setPinOperatingRate(false)"));
        assertTrue(oem.contains("maybeLogSwapStall"));
    }

    @Test
    public void glAndAcquirePathsLogStallsWithSentrySnapshot() throws IOException {
        String mosaic = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/surveillance/GpuMosaicRecorder.java");
        String camera = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/camera/PanoramicCameraGpu.java");
        String sentry = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/surveillance/SurveillanceEngineGpu.java");
        assertTrue(mosaic.contains("maybeLogDrawStall"));
        assertTrue(camera.contains("maybeLogAcquireStall"));
        assertTrue(sentry.contains("public String stallDiagnostics()"));
        assertTrue(sentry.contains("public boolean isInferenceInFlight()"));
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

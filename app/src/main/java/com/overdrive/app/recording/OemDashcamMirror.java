package com.overdrive.app.recording;

/**
 * Bridge between the pano dashcam recording lifecycle (RecordingModeManager)
 * and the OEM Dashcam pipeline.
 *
 * <p>Three modes the user picks on recording.html → OEM tab:
 * <ul>
 *   <li>{@code off}        — never start the OEM mirror.</li>
 *   <li>{@code continuous} — record dvr_*.mp4 the whole time the pipeline
 *                            is up, independent of pano dashcam state.</li>
 *   <li>{@code smart}      — mirror whatever the pano dashcam is doing.
 *                            Pano starts → OEM starts; pano stops → OEM
 *                            stops. dvr_*.mp4 stays aligned with cam_*.mp4
 *                            without the user managing two settings.</li>
 * </ul>
 *
 * <p>This class only handles the {@code recordingMode} side. The {@code
 * surveillanceMode} side fires from {@code SurveillanceEngineGpu} via the
 * existing OEM-event hooks.
 */
public final class OemDashcamMirror {
    private OemDashcamMirror() {}

    /** Pano dashcam started writing cam_*.mp4. Mirror to OEM iff smart. */
    public static void onPanoRecordingStarted() {
        if (!"smart".equals(getRecordingMode())) return;
        kickLifecycle();
    }

    /** Pano dashcam stopped writing cam_*.mp4. Stop the OEM mirror iff smart;
     *  applyTriggerLifecycleFromUcm decides whether the pipeline can fully
     *  tear down (no other consumer left) or merely stop recording.
     *
     *  <p>We DO NOT early-return on `!oem.isRecording()` — pano can stop
     *  before OEM finishes its 4-9s warm-up + waitForEncoderFormat, and
     *  in that race the recalc must still fire so the lifecycle worker
     *  picks up "recordingDesired=false" the moment pano went away.
     *  Otherwise the deferred OEM start would write a stranded dvr_*.mp4
     *  that nobody asked for. */
    public static void onPanoRecordingStopped() {
        if (!"smart".equals(getRecordingMode())) return;
        com.overdrive.app.server.OemDashcamApiHandler.scheduleLifecycleRecalc();
    }

    /** Resolved recordingMode: "off" | "continuous" | "smart". */
    private static String getRecordingMode() {
        try {
            return com.overdrive.app.config.UnifiedConfigManager.getOemRecordingMode();
        } catch (Throwable t) { return "off"; }
    }

    private static void kickLifecycle() {
        // Schedule on the dedicated lifecycle executor — it dedups burst
        // clicks and never blocks the caller. Pano recorder must not stall
        // waiting for OEM to come up.
        com.overdrive.app.server.OemDashcamApiHandler.scheduleLifecycleRecalc();
    }
}

package com.overdrive.app.roadsense.warn

import com.overdrive.app.config.UnifiedConfigManager
import com.overdrive.app.roadsense.detect.Severity
import org.json.JSONObject

/**
 * Daemon-side [WarningCoordinator.VisualSink] (D-024): turns the coordinator's
 * "show this approach / clear it" calls into an [OverlayState] written to UCM
 * `roadSense.overlayState`. The app-side `RoadSenseOverlayService` polls that and
 * renders — this class writes no UI, just state.
 *
 * Calibration level (the green/orange/red dot, R-OVL-1) isn't known to the
 * WarningCoordinator, so the controller supplies it via [calibrationLevelSupplier]
 * (typically `VehicleCalibrator.maturity`, optionally blended with confirmed-hazard
 * density later). A pending Calibration-Mode confirm is injected separately by the
 * controller via [setPendingConfirm] and rides along on the next write.
 *
 * Threading: written only on the daemon warn-tick thread (NOT a UI thread —
 * feedback_no_unified_writes_on_ui_thread). `updateSection` is a full-JSON rewrite,
 * so we COALESCE: only write when the rendered state actually changes (distance is
 * bucketed to whole metres + a min-interval), so we don't rewrite the config file
 * dozens of times a second. `clock` injected for testability.
 */
class UcmVisualSink(
    private val calibrationLevelSupplier: () -> Float,
    /** Route-coverage level of the CURRENT tile (0=new,1=seen,2=mapped) — the
     *  controller supplies it from RouteCoverage. Distinct from calibration. */
    private val coverageSupplier: () -> Int = { 0 },
    private val clock: () -> Long = { System.currentTimeMillis() },
) : WarningCoordinator.VisualSink {

    @Volatile private var pending: OverlayState.PendingConfirm? = null
    private var lastJson: String? = null
    private var lastWriteMs = 0L

    /** Controller sets/clears the Calibration-Mode confirm payload (D-025). */
    fun setPendingConfirm(p: OverlayState.PendingConfirm?) {
        pending = p
    }

    override fun showApproach(
        hazardId: String,
        rangeM: Double,
        relativeBearingDeg: Double,
        severity: Severity,
        typeOrdinal: Int,
        zoneCount: Int,
        zoneLengthM: Int,
        zoneRough: Boolean,
    ) {
        write(
            OverlayState(
                calibrationLevel = calibrationLevelSupplier(),
                coverage = coverageSupplier(),
                hazardAhead = true,
                nextHazardMeters = rangeM.toInt(),
                nextHazardRelBearingDeg = relativeBearingDeg.toInt(),
                nextHazardSeverity = severity.level,
                nextHazardType = typeOrdinal,
                zoneCount = zoneCount,
                zoneLengthM = zoneLengthM,
                zoneRough = zoneRough,
                pendingConfirm = pending,
                updatedMs = clock(),
            )
        )
    }

    override fun clearApproach() {
        write(OverlayState.idle(calibrationLevelSupplier(), coverageSupplier(), clock())
            .copy(pendingConfirm = pending))
    }

    /** Publish idle state on demand (controller calls this each warn-tick even
     *  when nothing's ahead, so the calibration dot + a fresh timestamp keep
     *  flowing and the app overlay knows the daemon is alive). */
    fun publishIdle() = clearApproach()

    private fun write(state: OverlayState) {
        val json = state.toJson()
        val str = json.toString()
        val now = clock()
        // Coalesce: skip if identical to last write AND we wrote recently. The
        // timestamp always differs, so compare WITHOUT it; rewrite at most every
        // MIN_WRITE_MS to keep a heartbeat even when nothing changes.
        val comparable = stripTimestamp(str)
        if (comparable == lastJson && (now - lastWriteMs) < MIN_WRITE_MS) return
        lastJson = comparable
        lastWriteMs = now
        try {
            // Cross-UID lost-update protection: we (the daemon) only own the
            // `overlayState` key, but updateSection rewrites the WHOLE roadSense
            // section by merging our key onto a re-read config. The APP owns
            // warnMode / pendingConfirmResult and writes them on user taps; a
            // stale-snapshot merge would clobber a just-toggled warnMode (the
            // "enable audio then visual → audio shows OFF" bug). updateSection
            // ALREADY guarantees this: it does loadConfigFresh() (full fresh-disk
            // re-read) INSIDE withConfigFileLock, so the merge is against the
            // latest committed bytes with no TOCTOU window. The explicit
            // forceReload() that used to precede this call is therefore REDUNDANT
            // (and was strictly worse — it read+parsed the ~6KB file a SECOND time
            // per write, outside the lock, fired up to 2 Hz while a hazard closes).
            // Removed. See UnifiedConfigManager.updateSection.
            UnifiedConfigManager.updateSection(
                OverlayState.SECTION,
                JSONObject().put(OverlayState.KEY, json),
            )
        } catch (_: Throwable) { /* overlay state is best-effort; never crash the tick */ }
    }

    private fun stripTimestamp(json: String): String =
        json.replace(Regex("\"ts\":\\d+,?"), "")

    companion object {
        /** Heartbeat floor: rewrite at least this often even if unchanged, so the
         *  app overlay's staleness check sees a live daemon. The overlay's staleness
         *  window is 4 s, so a 3 s heartbeat keeps it live with margin while cutting
         *  the idle full-config-rewrite rate ~3× vs 1 Hz (audit: UCM updateSection is
         *  a full-file JSON rewrite under a global lock the camera pipeline shares).
         *  Change-driven writes (a new/closer hazard) still go out immediately. */
        private const val MIN_WRITE_MS = 3_000L
    }
}

package com.overdrive.app.byd

import com.overdrive.app.camera.dilink5.DiLink5QCarCamBackend
import com.overdrive.app.logging.DaemonLogger

/**
 * Detects whether this device is running on BYD DiLink 5.0 hardware
 * (Qualcomm Snapdragon SA8155P — e.g. Sealion 7).
 *
 * Delegates entirely to [DiLink5QCarCamBackend.isSupported], which is
 * itself the OR of the native AIS/QCarCam probe
 * (`/vendor/lib64/libais_client.so`) and the user-configured
 * `camera.cameraMode` containing "dilink5" (the manual override in the
 * Ingestion Mode dialog, for a unit whose auto-detect fails). That single
 * method is what every DiLink5-gated call site in the app now calls —
 * this wrapper exists only for the `byd` package's naming/call-site
 * convenience, not as a second implementation of the check.
 *
 * [CarSvcTelemetry] uses this to gate its `dumpsys car_service` fallback
 * telemetry (gear / 12V battery / doors / charging) so that on every OTHER
 * platform — where the vendor HAL paths this fallback exists for are not
 * known to be broken — the new code path never runs at all.
 */
object DiLink5Platform {

    private val logger = DaemonLogger.getInstance("DiLink5Platform")

    /** True when this device is (or looks like) DiLink 5.0 / Sealion 7 class hardware. */
    @JvmStatic
    fun isActive(): Boolean {
        return try {
            DiLink5QCarCamBackend.isSupported()
        } catch (t: Throwable) {
            logger.debug("isActive() check failed, assuming not DiLink5: " + t.message)
            false
        }
    }
}

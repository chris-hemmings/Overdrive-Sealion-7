package com.overdrive.app.onboarding

import android.content.Context
import android.content.SharedPreferences

/**
 * Persistent state for the two-track onboarding guide (novice + expert).
 *
 * STORE CHOICE — app-private SharedPreferences, deliberately NOT UnifiedConfigManager.
 * The very first onboarding step (Step 0) authorizes the uid-2000 daemon; until that
 * grant lands the daemon is not up, so the unified config store (written atomically by
 * the daemon under /data/local/tmp, which the app UID cannot mkdir/rename — see
 * UnifiedConfigManager + the locale/auth split-brain notes) is unreachable from here.
 * Onboarding state is also a pure UI concern that no daemon ever reads. App-private
 * MODE_PRIVATE prefs are the correct, bootstrap-safe home and survive the camera-daemon
 * SIGKILL restart in the camera wizard (that kills the daemon, not this app process).
 *
 * This is intentionally separate from SetupGuideDialog's "overdrive_setup" prefs: that
 * dialog re-fires on every install via PackageInfo.lastUpdateTime (BYD wipes its
 * autostart allowlist per install). The guide must NOT re-arm on update — it is
 * once-per-user and only re-shows on explicit replay, so it gets its own version-
 * independent flags here.
 */
class OnboardingState private constructor(private val prefs: SharedPreferences) {

    /** Resume state for the camera-mapping chapter (strongly encouraged, resumable). */
    enum class CameraStep {
        NOT_STARTED,
        IN_PROGRESS,   // see cameraSubStep for where to resume
        SAVED_OK,
        DEFERRED;      // user chose "do this later" — re-offer on next launch

        companion object {
            fun fromName(name: String?): CameraStep =
                values().firstOrNull { it.name == name } ?: NOT_STARTED
        }
    }

    var daemonAuthorized: Boolean
        get() = prefs.getBoolean(KEY_DAEMON_AUTHORIZED, false)
        set(v) = prefs.edit().putBoolean(KEY_DAEMON_AUTHORIZED, v).apply()

    /**
     * True once the user has been asked to pick an operating mode (Vehicle ON+OFF vs
     * ON only) in the first-run step. This is only the "was it asked" marker — the
     * chosen mode VALUE lives in UnifiedConfig (surveillance.operatingMode) so the
     * daemons can read it; this app-private flag just drives whether the MODE step
     * still needs to show. Absent → step shows; the config default (onAndOff) already
     * means a user who never reaches it gets full behaviour.
     */
    var modeChosen: Boolean
        get() = prefs.getBoolean(KEY_MODE_CHOSEN, false)
        set(v) = prefs.edit().putBoolean(KEY_MODE_CHOSEN, v).apply()

    /**
     * A non-default operating mode the user picked that has NOT yet been durably written
     * to the daemon (the config default is "onAndOff", so only a non-default choice needs
     * this). Set the instant the user picks it; cleared only on a confirmed persist. A
     * daemon-ready flush (MainActivity.flushPendingOperatingMode) retries it independently
     * of the onboarding step lifecycle, so the choice can't be lost if the first-run POST
     * retries all expire before the daemon binds :8080 and the user then completes
     * onboarding (which would otherwise disable the MODE re-ask via onboardingComplete).
     * Null = nothing pending.
     */
    var pendingOperatingMode: String?
        get() = prefs.getString(KEY_PENDING_OP_MODE, null)
        set(v) = prefs.edit().apply {
            if (v == null) remove(KEY_PENDING_OP_MODE) else putString(KEY_PENDING_OP_MODE, v)
        }.apply()

    var cameraStep: CameraStep
        get() = CameraStep.fromName(prefs.getString(KEY_CAMERA_STEP, null))
        set(v) = prefs.edit().putString(KEY_CAMERA_STEP, v.name).apply()

    /** Sub-step index to resume the camera wizard at when cameraStep == IN_PROGRESS. */
    var cameraSubStep: Int
        get() = prefs.getInt(KEY_CAMERA_SUBSTEP, 0)
        set(v) = prefs.edit().putInt(KEY_CAMERA_SUBSTEP, v).apply()

    var vehicleStepDone: Boolean
        get() = prefs.getBoolean(KEY_VEHICLE_DONE, false)
        set(v) = prefs.edit().putBoolean(KEY_VEHICLE_DONE, v).apply()

    var windshieldStepDone: Boolean
        get() = prefs.getBoolean(KEY_WINDSHIELD_DONE, false)
        set(v) = prefs.edit().putBoolean(KEY_WINDSHIELD_DONE, v).apply()

    var dashboardTourDone: Boolean
        get() = prefs.getBoolean(KEY_DASHBOARD_TOUR_DONE, false)
        set(v) = prefs.edit().putBoolean(KEY_DASHBOARD_TOUR_DONE, v).apply()

    var onboardingComplete: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING_COMPLETE, false)
        set(v) = prefs.edit().putBoolean(KEY_ONBOARDING_COMPLETE, v).apply()

    /**
     * Where the Expert track should resume from (set by the novice exit CTA so
     * "Explore Setup" lands on the first chapter novice skipped, not the verbatim
     * repeat of chapter 1). Null = start at the beginning.
     */
    var expertTourEntry: String?
        get() = prefs.getString(KEY_EXPERT_ENTRY, null)
        set(v) = prefs.edit().putString(KEY_EXPERT_ENTRY, v).apply()

    /**
     * True when the novice track should auto-run on launch: not yet complete and
     * the daemon-auth gate hasn't been cleared, OR a resumable chapter is pending.
     * Cheap, synchronous — safe to call from onResume.
     */
    fun shouldAutoRunNovice(): Boolean {
        if (onboardingComplete) return false
        return true
    }

    /** Clear every flag — replays the whole track. Used by a hidden Diagnostics reset. */
    fun reset() {
        prefs.edit()
            .remove(KEY_DAEMON_AUTHORIZED)
            .remove(KEY_MODE_CHOSEN)
            .remove(KEY_PENDING_OP_MODE)
            .remove(KEY_CAMERA_STEP)
            .remove(KEY_CAMERA_SUBSTEP)
            .remove(KEY_VEHICLE_DONE)
            .remove(KEY_WINDSHIELD_DONE)
            .remove(KEY_DASHBOARD_TOUR_DONE)
            .remove(KEY_ONBOARDING_COMPLETE)
            .remove(KEY_EXPERT_ENTRY)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "overdrive_onboarding"

        private const val KEY_DAEMON_AUTHORIZED = "daemon_authorized"
        private const val KEY_MODE_CHOSEN = "mode_chosen"
        private const val KEY_PENDING_OP_MODE = "pending_operating_mode"
        private const val KEY_CAMERA_STEP = "camera_step"
        private const val KEY_CAMERA_SUBSTEP = "camera_sub_step"
        private const val KEY_VEHICLE_DONE = "vehicle_step_done"
        private const val KEY_WINDSHIELD_DONE = "windshield_step_done"
        private const val KEY_DASHBOARD_TOUR_DONE = "dashboard_tour_done"
        private const val KEY_ONBOARDING_COMPLETE = "onboarding_complete"
        private const val KEY_EXPERT_ENTRY = "expert_tour_entry"

        @Volatile
        private var instance: OnboardingState? = null

        @JvmStatic
        fun get(context: Context): OnboardingState {
            return instance ?: synchronized(this) {
                instance ?: OnboardingState(
                    context.applicationContext
                        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                ).also { instance = it }
            }
        }
    }
}

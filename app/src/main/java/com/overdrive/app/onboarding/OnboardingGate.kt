package com.overdrive.app.onboarding

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log

/**
 * Parked-only safety gate for the onboarding overlay.
 *
 * SAFETY CONTRACT: no blocking/full-screen onboarding may appear while the car is
 * being driven. The overlay shows only when parked, and any visible overlay is
 * dismissed immediately on an ACC/ignition-ON edge.
 *
 * SIGNAL CHOICE — the BYD broadcast, NOT AccMonitor.isAccOn(). AccMonitor's static
 * accOn lives in the DAEMON process (uid 2000) and is only updated by AccSentryDaemon
 * IPC into that process; in this app UI process it sits at its false default forever,
 * so reading it here is meaningless. The proven app-process signal is the same vendor
 * broadcast PinLockActivity keys off to minimize the keypad on ignition: we listen for
 * the ON-specific actions only (com.byd.action.ACC_ON / IGN_ON), which carry an
 * unambiguous direction — unlike com.byd.accmode.ACC_MODE_CHANGED which fires on both
 * edges. We default to "assume parked" (show is allowed) because the overlay auto-runs
 * at foreground launch, which is overwhelmingly a parked moment, and we self-dismiss the
 * instant a real ACC-ON edge arrives.
 *
 * Lifecycle: construct, call [register] when the overlay attaches, [unregister] when it
 * detaches. [onAccOn] fires on the looper thread for the host to tear the overlay down.
 */
class OnboardingGate(
    private val context: Context,
    private val onAccOn: () -> Unit,
) {
    private var receiver: BroadcastReceiver? = null

    /**
     * Best-effort "are we parked right now?". The broadcast is edge-driven (there is no
     * readable level in the app process), so steady-state we assume parked — true. The
     * real protection is the ACC-ON edge dismiss in [register]. Callers gate the INITIAL
     * show on this; the receiver handles the case where the car starts mid-overlay.
     */
    fun canShow(): Boolean = true

    fun register() {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    "com.byd.action.ACC_ON",
                    "com.byd.action.IGN_ON" -> {
                        // Unambiguous ON edge — the car is being driven. Tear the
                        // onboarding overlay down so it can never cover the driving
                        // screen. Mirrors PinLockActivity.registerAccOnReceiver.
                        Log.i(TAG, "ACC ON broadcast ${intent.action} — dismissing onboarding overlay")
                        try { onAccOn() } catch (t: Throwable) {
                            Log.w(TAG, "onAccOn handler threw: ${t.message}")
                        }
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction("com.byd.action.ACC_ON")
            addAction("com.byd.action.IGN_ON")
        }
        try {
            // targetSdk=25 is exempt from the API-34 mandatory-export-flag rule; these
            // are cross-UID vendor broadcasts from the BYD system UID, so the un-flagged
            // registration (RECEIVER_NOT_EXPORTED would silently drop them) is correct
            // here — identical to PinLockActivity / BydDataCollector / ScreenOffReceiver.
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(r, filter)
            receiver = r
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to register ACC-on receiver: ${t.message}")
        }
    }

    fun unregister() {
        val r = receiver ?: return
        try { context.unregisterReceiver(r) } catch (t: Throwable) {
            Log.w(TAG, "Failed to unregister ACC-on receiver: ${t.message}")
        }
        receiver = null
    }

    private companion object {
        const val TAG = "OnboardingGate"
    }
}

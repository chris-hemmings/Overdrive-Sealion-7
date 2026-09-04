package com.overdrive.app.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.google.android.material.button.MaterialButton
import com.overdrive.app.R
import com.overdrive.app.auth.PinManager
import com.overdrive.app.auth.PinSession
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * App-side PIN lock screen.
 *
 * Gates [MainActivity] only — the cam daemon, AccSentry, surveillance,
 * recording, Telegram alerts, status overlay, and Deterrent / Blocker
 * activities run independently. Configured via Settings → Security.
 *
 * Lifecycle:
 *  - Launched from MainActivity.onResume / onNewIntent when
 *    [PinSession.shouldGate] returns true.
 *  - On successful unlock → [PinSession.markUnlocked] + finish() →
 *    MainActivity (which is behind us in the task) becomes visible at
 *    its previous nav destination.
 *  - The hardware Back button moves the task to the home screen rather
 *    than dismissing the lock — preventing trivial bypass.
 *
 * Bypass protection:
 *  - [singleTop] in the manifest keeps this Activity from being stacked
 *    on itself; it lives in MainActivity's task. On unlock we
 *    finish() and the user lands back where they were.
 *  - [excludeFromRecents] keeps the keypad off the recents stack.
 *  - The hardware Back button is overridden to {@code moveTaskToBack},
 *    so dismissing the lock requires unlocking it.
 *  - MainActivity itself sets {@code FLAG_SECURE} on its window while
 *    locked so the OS recents thumbnail / task-switcher snapshot of
 *    the underlying app draws blank instead of leaking live-camera or
 *    dashboard content.
 */
class PinLockActivity : AppCompatActivity() {

    private val entered = StringBuilder()
    private val dots = mutableListOf<View>()
    private lateinit var titleView: TextView
    private lateinit var subtitleView: TextView
    private lateinit var dotRow: LinearLayout
    private lateinit var keypad: View
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lockoutTimer: CountDownTimer? = null

    /**
     * True only after onCreate has run setContentView + bound all
     * lateinits. The early-return safety-net path in onCreate (when the
     * lock has been disabled mid-launch) skips inflate, but Android
     * still delivers onPause/onResume/onDestroy before destruction —
     * those handlers must not touch the unbound view references.
     */
    private var inflated: Boolean = false

    /**
     * Background executor for PinManager.verify — PBKDF2 is ~150ms on the
     * BYD CPU and the verify path also does a UnifiedConfigManager write
     * for failed-attempt counters. Both must stay off the looper or the
     * keypad freezes for ~150ms per submission (per memory
     * feedback_no_unified_writes_on_ui_thread).
     */
    private var verifyExecutor: ExecutorService? = null
    @Volatile private var verifyInFlight: Boolean = false

    /**
     * Minimizes the keypad when ACC turns ON.
     *
     * The PIN screen can reach the foreground on a non-user background launch
     * (app-update relaunch, daemon/keepalive `am start`, process revival) while
     * ACC is OFF — those launch paths don't carry the boot `minimize_on_start`
     * suppression, so [MainActivity.maybeShowPinLock] gates and pushes us on
     * top. When the driver then turns ACC ON, the keypad would otherwise sit
     * over the BYD home screen with nothing to dismiss it. BootReceiver already
     * receives these BYD ACC broadcasts; we register a process-local receiver
     * for the same actions and [moveTaskToBack] (NOT finish) on the ON edge so
     * MainActivity stays behind us with the PIN gate still armed.
     *
     * Event-driven by design: no polling, no HAL probe. AccMonitor.probeAccState
     * blocks the caller up to ~400ms on a sentinel reading (Thread.sleep retry
     * loop) and is unproven from the app UID, so it must not be polled on the
     * keypad's looper.
     */
    private var accOnReceiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // Show over keyguard / wake on launch so a notification tap on a
        // dimmed panel still surfaces the lock screen rather than the
        // OS keyguard's confused stack.
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        if (!PinManager.isEnabled()) {
            // Safety net — settings disabled the PIN while we were spawning.
            // Don't markUnlocked here: that would grant access without any
            // verification on a transient false reading from isEnabled()
            // (cross-UID UCM merge race or EACCES). Just finish — if the
            // lock is genuinely disabled, MainActivity.maybeShowPinLock
            // will see !isEnabled() on its next onResume and not re-spawn.
            finish()
            return
        }

        setContentView(R.layout.activity_pin_lock)

        titleView = findViewById(R.id.tvPinTitle)
        subtitleView = findViewById(R.id.tvPinSubtitle)
        dotRow = findViewById(R.id.pinDotRow)
        keypad = findViewById(R.id.pinKeypadGrid)

        for (i in 0 until dotRow.childCount) dots.add(dotRow.getChildAt(i))

        // Bind the recovery hint with the translatable prefix + the
        // translatable=false literal command, so a future edit to the
        // command in settings_security_recovery_command propagates to
        // the lock-screen surface automatically.
        findViewById<TextView?>(R.id.tvPinRecoveryHint)?.text = getString(
            R.string.pin_lock_recovery_hint_prefix,
            getString(R.string.settings_security_recovery_command)
        )

        wireKeypad()
        inflated = true
        renderDots()

        val info = PinManager.getLockoutInfo()
        if (info.lockedOut) startLockoutCountdown(info.remainingMs)

        registerAccOnReceiver()
    }

    /**
     * Register a process-local receiver for the BYD ACC-ON broadcasts so the
     * keypad self-minimizes when the car wakes. Idempotent.
     *
     * Registered with the plain 2-arg form (no export flag) to match the
     * codebase convention for external broadcasts — BydDataCollector's
     * plug-edge receiver and ScreenOffReceiver register the same way. These
     * are vendor broadcasts sent from the BYD system UID, so the receiver
     * MUST be able to receive cross-UID broadcasts; RECEIVER_NOT_EXPORTED
     * would silently block them. targetSdk=25 is exempt from the API-34
     * mandatory-flag requirement, so the un-flagged form is safe on the head
     * unit and preserves external delivery.
     */
    private fun registerAccOnReceiver() {
        if (accOnReceiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    "com.byd.action.ACC_ON",
                    "com.byd.action.IGN_ON" -> {
                        // Unambiguous ACC/ignition ON edge — minimize the keypad.
                        // We deliberately listen ONLY to the ON-specific actions,
                        // not com.byd.accmode.ACC_MODE_CHANGED, which fires on BOTH
                        // edges and carries no readable direction here: keying off
                        // it would let an ACC-OFF transition dismiss a keypad the
                        // user is actively typing into. moveTaskToBack just sends
                        // the task behind home — the PIN gate stays armed and
                        // re-fires on the next foreground entry. NOT finish().
                        android.util.Log.i("PinLockActivity",
                            "ACC ON broadcast ${intent.action} — minimizing keypad")
                        moveTaskToBack(true)
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction("com.byd.action.ACC_ON")
            addAction("com.byd.action.IGN_ON")
        }
        try {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(r, filter)
            accOnReceiver = r
        } catch (t: Throwable) {
            android.util.Log.w("PinLockActivity",
                "Failed to register ACC-on receiver: ${t.message}")
        }
    }

    private fun unregisterAccOnReceiver() {
        val r = accOnReceiver ?: return
        accOnReceiver = null
        try {
            unregisterReceiver(r)
        } catch (t: Throwable) {
            // Already unregistered / never registered — benign.
        }
    }

    override fun onPause() {
        super.onPause()
        // Skip the wipe on the early-return path (no inflate) and on
        // configuration-change recreate (rotation). The recreate-skip
        // is purely defensive: a rotated instance gets a fresh empty
        // StringBuilder anyway since `entered` is an instance member,
        // so the explicit wipe would be redundant — but skipping it on
        // !isChangingConfigurations clarifies the intent for the reader
        // and avoids a momentarily-empty paint during rotation if a
        // future change ever propagates state across recreates.
        if (!inflated) return
        if (!isFinishing && !isChangingConfigurations) {
            entered.setLength(0)
            mainHandler.removeCallbacks(autoSubmit)
        }
    }

    override fun onResume() {
        super.onResume()
        // The early-return path in onCreate finishes the activity without
        // inflating. Android still delivers onResume between then and
        // destruction — bail before touching any lateinit view ref.
        if (!inflated) return
        // Re-paint after onPause's clear and re-check lockout state in
        // case the persisted lockoutUntil has elapsed in the background.
        renderDots()
        val info = PinManager.getLockoutInfo()
        if (info.lockedOut && lockoutTimer == null) startLockoutCountdown(info.remainingMs)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterAccOnReceiver()
        lockoutTimer?.cancel()
        lockoutTimer = null
        verifyExecutor?.shutdownNow()
        verifyExecutor = null
        // Cancel any in-flight shake animation so its end-callback can't
        // touch a destroyed view (and so the ViewPropertyAnimator stops
        // holding the dotRow → activity reference). Inflated guard
        // covers the early-return path that never bound dotRow.
        if (inflated && ::dotRow.isInitialized) {
            dotRow.animate().cancel()
            dotRow.clearAnimation()
        }
        // Defensive: zero-out any held PIN material before the activity is
        // garbage-collected — but only on real finishes. Rotation recreate
        // hands the same StringBuilder to the new instance via the static
        // field on the class? — no, this is an instance member so a fresh
        // recreate gets a fresh StringBuilder. Safe to clear unconditionally.
        entered.setLength(0)
        mainHandler.removeCallbacksAndMessages(null)
    }

    private fun verifyExecutor(): ExecutorService =
        verifyExecutor ?: Executors.newSingleThreadExecutor { r ->
            Thread(r, "PinVerify").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY
            }
        }.also { verifyExecutor = it }

    override fun onBackPressed() {
        // Never dismiss — go home instead. Closes the bypass where back
        // would just reveal MainActivity.
        moveTaskToBack(true)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Numeric keypad support (BYD steering-wheel buttons / external
        // USB keypad / debug). Maps KEYCODE_0..9 to digit entry, DEL to
        // backspace.
        if (keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
            appendDigit(('0' + (keyCode - KeyEvent.KEYCODE_0)).toString())
            return true
        }
        if (keyCode in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9) {
            appendDigit(('0' + (keyCode - KeyEvent.KEYCODE_NUMPAD_0)).toString())
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_DEL) {
            backspace()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun wireKeypad() {
        val digitIds = intArrayOf(
            R.id.btnPin0, R.id.btnPin1, R.id.btnPin2, R.id.btnPin3, R.id.btnPin4,
            R.id.btnPin5, R.id.btnPin6, R.id.btnPin7, R.id.btnPin8, R.id.btnPin9
        )
        for (id in digitIds) {
            val btn = findViewById<MaterialButton>(id)
            btn.setOnClickListener { v ->
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                appendDigit(btn.text.toString())
            }
        }
        findViewById<MaterialButton>(R.id.btnPinBackspace).setOnClickListener { v ->
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            backspace()
        }
    }

    private fun appendDigit(digit: String) {
        if (lockoutTimer != null) return // ignore taps while locked out
        if (verifyInFlight) return       // PBKDF2 in flight — discard input
        if (entered.length >= MAX_PIN_LEN) return
        entered.append(digit)
        renderDots()
        // Auto-submit at min length to feel snappy; fall through to manual
        // submit at >= MIN if user keeps typing then auto-submits at MAX.
        if (entered.length == MAX_PIN_LEN) {
            attemptUnlock()
        } else if (entered.length >= MIN_AUTO_SUBMIT_LEN) {
            // Defer briefly so the user can keep typing a longer PIN.
            mainHandler.removeCallbacks(autoSubmit)
            mainHandler.postDelayed(autoSubmit, AUTO_SUBMIT_DELAY_MS)
        }
    }

    private val autoSubmit = Runnable {
        if (entered.length >= MIN_AUTO_SUBMIT_LEN) attemptUnlock()
    }

    private fun backspace() {
        if (lockoutTimer != null) return
        if (verifyInFlight) return
        if (entered.isEmpty()) return
        mainHandler.removeCallbacks(autoSubmit)
        entered.deleteCharAt(entered.length - 1)
        renderDots()
    }

    /**
     * Propagate enabled-state to every child of the keypad GridLayout.
     * GridLayout doesn't cascade setEnabled to children, so we have to
     * walk them manually — otherwise the buttons stay clickable visually
     * disabled.
     */
    private fun setKeypadEnabled(enabled: Boolean) {
        keypad.alpha = if (enabled) 1f else 0.7f
        if (keypad is android.view.ViewGroup) {
            val vg = keypad as android.view.ViewGroup
            for (i in 0 until vg.childCount) {
                val child = vg.getChildAt(i)
                // Only flip MaterialButton children — the keypad grid also
                // contains a Space placeholder (and may grow other
                // decorative views later). MaterialButton is the only
                // type whose isEnabled gate matters here.
                if (child is com.google.android.material.button.MaterialButton) {
                    child.isEnabled = enabled
                }
            }
        }
    }

    private fun attemptUnlock() {
        mainHandler.removeCallbacks(autoSubmit)
        if (verifyInFlight) return
        val candidate = entered.toString()
        verifyInFlight = true
        // Disable keypad while in flight so the user can't queue up dozens of
        // verify calls during the 150ms PBKDF2 spin. Re-enabled in the post-back.
        // GridLayout doesn't cascade isEnabled, so walk children explicitly.
        setKeypadEnabled(false)

        verifyExecutor().execute {
            val result = try { PinManager.verify(candidate) } catch (t: Throwable) {
                android.util.Log.w("PinLockActivity", "verify threw: ${t.message}")
                PinManager.VerifyResult.WRONG
            }
            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                // Re-enable input ONLY on branches that keep the activity
                // alive. Leaving the keypad enabled (or verifyInFlight=false)
                // during the OK success animation window lets a hardware
                // key press (BYD steering-wheel button / external USB
                // numpad) feed digits through onKeyDown — bypassing the
                // touch-disabled state and persisting a stray failed-
                // attempt counter increment on the next session.
                when (result) {
                    PinManager.VerifyResult.OK -> {
                        // Keep verifyInFlight=true so appendDigit/backspace's
                        // own guards drop hardware key input too. Reset
                        // happens implicitly when the activity finishes;
                        // it's a per-instance @Volatile member and the
                        // next session creates a fresh PinLockActivity
                        // with verifyInFlight=false by default.
                        // Clear secret material before transitioning so a
                        // heap dump on the way out doesn't capture the PIN.
                        entered.setLength(0)
                        titleView.setText(R.string.pin_lock_title_unlocked)
                        subtitleView.text = ""
                        PinSession.markUnlocked()
                        mainHandler.postDelayed({
                            setResult(RESULT_OK)
                            finish()
                            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                        }, 120L)
                    }
                    PinManager.VerifyResult.WRONG -> {
                        verifyInFlight = false
                        setKeypadEnabled(true)
                        shakeAndClear(R.string.pin_lock_subtitle_wrong)
                    }
                    PinManager.VerifyResult.LOCKED_OUT -> {
                        verifyInFlight = false
                        val info = PinManager.getLockoutInfo()
                        // Don't re-enable here — startLockoutCountdown will
                        // dim/disable the keypad for the lockout window and
                        // re-enable when the timer fires.
                        shakeAndClear(R.string.pin_lock_subtitle_locked_out)
                        if (info.remainingMs > 0L) startLockoutCountdown(info.remainingMs)
                    }
                    PinManager.VerifyResult.NOT_ENABLED -> {
                        verifyInFlight = false
                        PinSession.markUnlocked()
                        finish()
                    }
                }
            }
        }
    }

    private fun shakeAndClear(@androidx.annotation.StringRes subtitleRes: Int) {
        subtitleView.setText(subtitleRes)
        subtitleView.setTextColor(getColorAttr(androidx.appcompat.R.attr.colorError))
        // Subtle shake on the dot row.
        dotRow.animate()
            .translationX(-16f).setDuration(40L)
            .withEndAction {
                dotRow.animate().translationX(16f).setDuration(60L)
                    .withEndAction {
                        dotRow.animate().translationX(0f).setDuration(40L).start()
                    }.start()
            }.start()
        entered.setLength(0)
        mainHandler.postDelayed({ renderDots() }, 250L)
    }

    private fun startLockoutCountdown(remainingMs: Long) {
        setKeypadEnabled(false)
        keypad.alpha = 0.4f // distinct from in-flight 0.7f to differentiate states
        lockoutTimer?.cancel()
        lockoutTimer = object : CountDownTimer(remainingMs, 1000L) {
            override fun onTick(msLeft: Long) {
                val s = (msLeft / 1000L).coerceAtLeast(1L)
                subtitleView.text = getString(R.string.pin_lock_subtitle_locked_out_countdown, formatLockoutTime(s))
                subtitleView.setTextColor(getColorAttr(androidx.appcompat.R.attr.colorError))
            }
            override fun onFinish() {
                lockoutTimer = null
                setKeypadEnabled(true)
                subtitleView.setText(R.string.pin_lock_subtitle_default)
                subtitleView.setTextColor(getColorAttr(com.google.android.material.R.attr.colorOnSurfaceVariant))
            }
        }.start()
    }

    private fun formatLockoutTime(totalSeconds: Long): String {
        if (totalSeconds < 60L) return getString(R.string.pin_lock_lockout_seconds, totalSeconds)
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return getString(R.string.pin_lock_lockout_minutes, minutes, seconds)
    }

    private fun renderDots() {
        val len = entered.length
        // Show only as many dots as digits entered, plus one trailing
        // empty placeholder until we hit MAX_PIN_LEN. This avoids
        // leaking the user's chosen PIN length to a shoulder-surfer
        // (an 8-dot row pre-filled with 4 empty slots reveals the user
        // has a 4-digit PIN before they finish typing).
        val visible = (len + 1).coerceAtMost(MAX_PIN_LEN).coerceAtLeast(MIN_VISIBLE_DOTS)
        for (i in dots.indices) {
            val v = dots[i]
            v.visibility = if (i < visible) View.VISIBLE else View.GONE
            v.setBackgroundResource(
                if (i < len) R.drawable.pin_dot_filled
                else R.drawable.pin_dot_empty
            )
        }
        // Live region for TalkBack — announces the current count.
        dotRow.contentDescription = resources.getQuantityString(
            R.plurals.pin_lock_dot_a11y, len, len
        )
        // Reset subtitle to default on any input (clears prior error state).
        if (lockoutTimer == null && len > 0) {
            subtitleView.setText(R.string.pin_lock_subtitle_default)
            subtitleView.setTextColor(getColorAttr(com.google.android.material.R.attr.colorOnSurfaceVariant))
        }
    }

    private fun getColorAttr(attr: Int): Int {
        val tv = android.util.TypedValue()
        theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    private companion object {
        // Mirrored from PinManager so the activity stays aligned with the
        // verifier's hard limits — see PinManager.MIN_PIN_LEN / MAX_PIN_LEN.
        val MAX_PIN_LEN = PinManager.MAX_PIN_LEN
        val MIN_AUTO_SUBMIT_LEN = PinManager.MIN_PIN_LEN
        const val AUTO_SUBMIT_DELAY_MS = 750L
        // Always show at least this many dots even when nothing has been
        // typed yet — gives the user a visual hint that input is expected.
        val MIN_VISIBLE_DOTS = PinManager.MIN_PIN_LEN
    }
}

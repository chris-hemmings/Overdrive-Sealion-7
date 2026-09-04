package com.overdrive.app.onboarding

import android.app.Activity
import android.view.View
import com.overdrive.app.R

/**
 * Dashboard orientation tour: teaches that the metric TILES navigate (shortcuts to the
 * same rail destinations) while the hero status CHIPS are display-only. Chips exist ONLY
 * in portrait (absent from layout-land), so the chip step degrades to a centered card in
 * landscape rather than spotlighting a missing view.
 *
 * Anchors are resolved from the live DashboardFragment root, fetched via MainActivity.
 */
class DashboardTourCoach(
    private val activity: Activity,
    private val overlay: OnboardingOverlayView,
    private val state: OnboardingState,
    private val onFinished: () -> Unit,
) {
    private fun dashboardRoot(): View? =
        (activity as? com.overdrive.app.ui.MainActivity)?.currentDashboardRoot()

    fun begin() {
        if (state.dashboardTourDone) { onFinished(); return }
        overlay.consumeCutoutTouch = false
        stepTiles()
    }

    private fun stepTiles() {
        val root = dashboardRoot()
        val quickLive = root?.findViewById<View>(R.id.quickLive)
        if (quickLive != null) overlay.spotlight(quickLive, animate = false)
        else overlay.showCentered()
        overlay.bindStep(
            title = activity.getString(R.string.onboarding_dash_tiles_title),
            body = activity.getString(R.string.onboarding_dash_tiles_body),
            primaryText = activity.getString(R.string.onboarding_next),
            onPrimary = { stepChips() },
            secondaryText = activity.getString(R.string.onboarding_skip),
            onSecondary = { finish() },
        )
    }

    private fun stepChips() {
        val root = dashboardRoot()
        // Portrait-only — null in landscape; degrade gracefully.
        val chip = root?.findViewById<View>(R.id.heroChipTunnel)
        if (chip != null) {
            overlay.spotlight(chip)
            overlay.bindStep(
                title = activity.getString(R.string.onboarding_dash_chips_title),
                body = activity.getString(R.string.onboarding_dash_chips_body),
                primaryText = activity.getString(R.string.onboarding_done_short),
                onPrimary = { finish() },
            )
        } else {
            // Landscape: no chips. Re-anchor on the hero card with degraded copy.
            val hero = root?.findViewById<View>(R.id.heroCard)
            if (hero != null) overlay.spotlight(hero) else overlay.showCentered()
            overlay.bindStep(
                title = activity.getString(R.string.onboarding_dash_chips_title),
                body = activity.getString(R.string.onboarding_dash_chips_body_land),
                primaryText = activity.getString(R.string.onboarding_done_short),
                onPrimary = { finish() },
            )
        }
    }

    private fun finish() {
        state.dashboardTourDone = true
        onFinished()
    }
}

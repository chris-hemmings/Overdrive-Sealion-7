package com.overdrive.app.onboarding

import android.view.animation.Interpolator
import android.view.animation.PathInterpolator

/**
 * Shared M3 motion vocabulary for the onboarding overlay, reused verbatim from the
 * app's existing motion (design-tokens durations 180/240/320 and the emphasized
 * cubic curve) so the guide feels native, not bolted-on.
 *
 * All values are tuned for a parked head-unit: short, decisive, no slow flourishes.
 */
object OnboardingMotion {

    /**
     * Material 3 "emphasized decelerate" — settles fast then eases. Identical control
     * points to the curve the app already uses for zoom/drill-down motion. This is what
     * makes the spotlight cutout read as gliding onto its next target.
     */
    @JvmField
    val EMPHASIZED: Interpolator = PathInterpolator(0.05f, 0.7f, 0.1f, 1.0f)

    /** Standard decelerate for content fades. */
    @JvmField
    val DECELERATE: Interpolator = PathInterpolator(0.0f, 0.0f, 0.2f, 1.0f)

    // Durations (ms) — mirror design-tokens.json duration tiers.
    const val DURATION_SHORT = 180L       // quick fades, card content swap
    const val DURATION_MEDIUM = 240L      // same-screen cutout hop
    const val DURATION_LONG = 320L        // base for a long cutout travel
    const val DURATION_MORPH_MAX = 360L   // cap for a full-screen diagonal cutout glide
    const val DURATION_EMPHASIS = 420L    // one-shot attention ring pulse
}

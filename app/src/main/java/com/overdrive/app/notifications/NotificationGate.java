package com.overdrive.app.notifications;

import com.overdrive.app.surveillance.Actor;
import com.overdrive.app.surveillance.SurveillanceConfig;

/**
 * NotificationGate — Decides whether a recording's peak severity warrants a
 * push notification, given user-configured tier toggles.
 *
 * Single decision function so the rule lives in one place. Defaults match the
 * SurveillanceConfig defaults: NOTICE off, ALERT on, CRITICAL on.
 */
public final class NotificationGate {

    private NotificationGate() {}

    public static boolean shouldPush(Actor.Severity sev, SurveillanceConfig cfg) {
        if (cfg == null) {
            // Null config means "config not yet loaded" — never silently
            // drop a notification on the user, especially since the
            // start-stage banner already published unconditionally on
            // a null config. Suppressing the final stage would leave the
            // user with a stale "Recording in progress…" banner that
            // never gets replaced.
            return true;
        }
        if (sev == null) return true;  // unknown severity → don't drop
        switch (sev) {
            case CRITICAL: return cfg.isPushCritical();
            case ALERT:    return cfg.isPushAlerts();
            case NOTICE:   return cfg.isPushNotices();
            default:       return false;
        }
    }

    /**
     * Convenience: derive recording-level severity from a list of actors and
     * decide whether to push.
     */
    public static boolean shouldPush(java.util.List<Actor> actors, SurveillanceConfig cfg) {
        return shouldPush(maxSeverity(actors), cfg);
    }

    /**
     * Telegram-tier gate. Reads the per-severity tier toggles
     * (tierNotices/tierAlerts/tierCritical) from
     * {@link com.overdrive.app.telegram.config.UnifiedTelegramConfig} so
     * the daemon process and the app process see the same value the
     * moment the user flips the toggle — no daemon restart needed.
     *
     * The {@code SurveillanceConfig} parameter is ignored and kept only
     * so existing call sites don't have to change argument shape.
     * forceReload() bypasses the per-process mtime cache so a write from
     * the OTHER UID is picked up immediately.
     *
     * Unknown-severity-allows-through is preserved — never silently drop
     * a notification of an unrecognised severity.
     */
    public static boolean shouldTelegram(Actor.Severity sev, SurveillanceConfig cfg) {
        if (sev == null) return true;
        try {
            com.overdrive.app.config.UnifiedConfigManager.forceReload();
            switch (sev) {
                case CRITICAL: return com.overdrive.app.telegram.config.UnifiedTelegramConfig.isTierCritical();
                case ALERT:    return com.overdrive.app.telegram.config.UnifiedTelegramConfig.isTierAlerts();
                case NOTICE:   return com.overdrive.app.telegram.config.UnifiedTelegramConfig.isTierNotices();
                default:       return false;
            }
        } catch (Exception e) {
            // Read failure shouldn't suppress an alert — the user-visible
            // failure mode for "nothing arrives" is much worse than
            // "something arrives that the user didn't want".
            return true;
        }
    }

    public static boolean shouldTelegram(java.util.List<Actor> actors, SurveillanceConfig cfg) {
        return shouldTelegram(maxSeverity(actors), cfg);
    }

    public static Actor.Severity maxSeverity(java.util.List<Actor> actors) {
        if (actors == null || actors.isEmpty()) return Actor.Severity.NOTICE;
        Actor.Severity max = Actor.Severity.NOTICE;
        for (Actor a : actors) {
            if (a.peakSeverity != null && a.peakSeverity.ordinal() > max.ordinal()) {
                max = a.peakSeverity;
            }
        }
        return max;
    }
}

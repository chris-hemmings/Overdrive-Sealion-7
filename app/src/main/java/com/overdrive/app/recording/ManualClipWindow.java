package com.overdrive.app.recording;

/**
 * Validated time window for a manually triggered recording clip.
 */
public final class ManualClipWindow {
    public static final int MAX_SECONDS = 60;

    private final int beforeSeconds;
    private final int afterSeconds;

    private ManualClipWindow(int beforeSeconds, int afterSeconds) {
        this.beforeSeconds = beforeSeconds;
        this.afterSeconds = afterSeconds;
    }

    public static ManualClipWindow create(int beforeSeconds, int afterSeconds) {
        if (beforeSeconds < 0 || beforeSeconds > MAX_SECONDS) {
            throw new IllegalArgumentException(
                    "beforeSeconds must be between 0 and " + MAX_SECONDS);
        }
        if (afterSeconds < 0 || afterSeconds > MAX_SECONDS) {
            throw new IllegalArgumentException(
                    "afterSeconds must be between 0 and " + MAX_SECONDS);
        }

        int totalSeconds = beforeSeconds + afterSeconds;
        if (totalSeconds < 1 || totalSeconds > MAX_SECONDS) {
            throw new IllegalArgumentException(
                    "clip duration must be between 1 and " + MAX_SECONDS + " seconds");
        }

        return new ManualClipWindow(beforeSeconds, afterSeconds);
    }

    public int getBeforeSeconds() {
        return beforeSeconds;
    }

    public int getAfterSeconds() {
        return afterSeconds;
    }

    public int getTotalSeconds() {
        return beforeSeconds + afterSeconds;
    }
}

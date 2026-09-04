package com.overdrive.app.proximity;

import com.overdrive.app.logging.DaemonLogger;

/**
 * Proximity Guard Configuration POJO
 * 
 * Immutable configuration for Proximity Guard recording mode.
 * Uses Builder pattern for construction.
 */
public class ProximityGuardConfig {
    private static final DaemonLogger logger = DaemonLogger.getInstance("ProximityGuardConfig");
    
    /**
     * Trigger sensitivity levels.
     */
    public enum TriggerLevel {
        RED,         // Only trigger on very close objects (0-0.5m)
        YELLOW_RED   // Trigger on medium-close objects (0-0.8m)
    }
    
    // Configuration fields
    private final boolean enabled;
    private final TriggerLevel triggerLevel;
    private final int preRecordSeconds;
    private final int postRecordSeconds;
    // Idle (MONITORING) recording profile. While waiting for a radar trigger,
    // the recording encoder is fed a low-rate, low-bitrate pre-record ring to
    // minimise GPU encode / drain / disk cost; on a trigger it snaps to the
    // camera's full rate + the event bitrate, and reverts when the event ends.
    // monitorFps drives the recorder draw stride (stride = round(cameraFps /
    // monitorFps)); monitorBitrate is applied directly to the encoder.
    private final int monitorFps;
    private final int monitorBitrate;
    private final int eventBitrate;
    private final boolean lowPowerMonitor;  // master enable for the idle profile

    // Validation constants
    private static final int MIN_PRE_RECORD_SECONDS = 2;
    private static final int MAX_PRE_RECORD_SECONDS = 15;
    private static final int MIN_POST_RECORD_SECONDS = 5;
    private static final int MAX_POST_RECORD_SECONDS = 30;
    private static final int MIN_MONITOR_FPS = 1;
    private static final int MAX_MONITOR_FPS = 15;
    // 8 fps idle-monitor rate. Raised from 4: the pre-record ring fills at this
    // rate (stride = round(cameraFps / monitorFps)), so 8 gives a smoother
    // pre-roll and — because the encoder's frame-count GOP lands every ~4 s
    // instead of ~8 s at 4 fps — better keyframe coverage inside the
    // pre-record window. Cost is a modest, bounded bump in idle encode frame
    // rate on the shared video block; bitrate (monitorBitrate) and pre-record
    // RAM (sized by bitrate×seconds, not fps) are unchanged, and the camera HAL
    // capture rate is owned elsewhere so OEM-capture cost is unaffected. Audio
    // is captured in a separate process at a fixed sample rate and is fully
    // decoupled from this value.
    private static final int DEFAULT_MONITOR_FPS = 8;
    private static final int MIN_BITRATE = 500_000;     // 0.5 Mbps floor
    private static final int MAX_BITRATE = 12_000_000;   // 12 Mbps ceiling
    private static final int DEFAULT_MONITOR_BITRATE = 1_500_000;  // 1.5 Mbps idle
    private static final int DEFAULT_EVENT_BITRATE = 6_000_000;    // 6 Mbps on trigger

    private ProximityGuardConfig(Builder builder) {
        this.enabled = builder.enabled;
        this.triggerLevel = builder.triggerLevel;
        this.preRecordSeconds = validatePreRecordSeconds(builder.preRecordSeconds);
        this.postRecordSeconds = validatePostRecordSeconds(builder.postRecordSeconds);
        this.monitorFps = validateMonitorFps(builder.monitorFps);
        this.monitorBitrate = validateBitrate(builder.monitorBitrate, DEFAULT_MONITOR_BITRATE);
        this.eventBitrate = validateBitrate(builder.eventBitrate, DEFAULT_EVENT_BITRATE);
        this.lowPowerMonitor = builder.lowPowerMonitor;
    }
    
    // ==================== GETTERS ====================
    
    public boolean isEnabled() {
        return enabled;
    }
    
    public TriggerLevel getTriggerLevel() {
        return triggerLevel;
    }
    
    public int getPreRecordSeconds() {
        return preRecordSeconds;
    }
    
    public int getPostRecordSeconds() {
        return postRecordSeconds;
    }

    public int getMonitorFps() {
        return monitorFps;
    }

    public int getMonitorBitrate() {
        return monitorBitrate;
    }

    public int getEventBitrate() {
        return eventBitrate;
    }

    public boolean isLowPowerMonitor() {
        return lowPowerMonitor;
    }

    // ==================== VALIDATION ====================
    
    private int validatePreRecordSeconds(int seconds) {
        if (seconds < MIN_PRE_RECORD_SECONDS) {
            logger.warn("preRecordSeconds " + seconds + " < min " + MIN_PRE_RECORD_SECONDS + ", clamping");
            return MIN_PRE_RECORD_SECONDS;
        }
        if (seconds > MAX_PRE_RECORD_SECONDS) {
            logger.warn("preRecordSeconds " + seconds + " > max " + MAX_PRE_RECORD_SECONDS + ", clamping");
            return MAX_PRE_RECORD_SECONDS;
        }
        return seconds;
    }
    
    private int validatePostRecordSeconds(int seconds) {
        if (seconds < MIN_POST_RECORD_SECONDS) {
            logger.warn("postRecordSeconds " + seconds + " < min " + MIN_POST_RECORD_SECONDS + ", clamping");
            return MIN_POST_RECORD_SECONDS;
        }
        if (seconds > MAX_POST_RECORD_SECONDS) {
            logger.warn("postRecordSeconds " + seconds + " > max " + MAX_POST_RECORD_SECONDS + ", clamping");
            return MAX_POST_RECORD_SECONDS;
        }
        return seconds;
    }

    private int validateMonitorFps(int fps) {
        if (fps < MIN_MONITOR_FPS) {
            logger.warn("monitorFps " + fps + " < min " + MIN_MONITOR_FPS + ", clamping");
            return MIN_MONITOR_FPS;
        }
        if (fps > MAX_MONITOR_FPS) {
            logger.warn("monitorFps " + fps + " > max " + MAX_MONITOR_FPS + ", clamping");
            return MAX_MONITOR_FPS;
        }
        return fps;
    }

    private int validateBitrate(int bitrate, int fallback) {
        if (bitrate < MIN_BITRATE) {
            logger.warn("bitrate " + bitrate + " < min " + MIN_BITRATE + ", using " + fallback);
            return fallback;
        }
        if (bitrate > MAX_BITRATE) {
            logger.warn("bitrate " + bitrate + " > max " + MAX_BITRATE + ", clamping");
            return MAX_BITRATE;
        }
        return bitrate;
    }

    // ==================== BUILDER ====================
    
    public static class Builder {
        // DEPRECATED: enabled flag is now controlled by RecordingModeManager.mode
        // Kept for backward compatibility but defaults to true
        private boolean enabled = true;
        // Default to YELLOW_RED for better sensitivity - triggers on medium-close objects
        private TriggerLevel triggerLevel = TriggerLevel.YELLOW_RED;
        private int preRecordSeconds = 5;
        private int postRecordSeconds = 10;
        private int monitorFps = DEFAULT_MONITOR_FPS;
        private int monitorBitrate = DEFAULT_MONITOR_BITRATE;
        private int eventBitrate = DEFAULT_EVENT_BITRATE;
        private boolean lowPowerMonitor = true;  // idle profile on by default

        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }
        
        public Builder triggerLevel(TriggerLevel triggerLevel) {
            this.triggerLevel = triggerLevel;
            return this;
        }
        
        public Builder triggerLevel(String triggerLevelStr) {
            try {
                this.triggerLevel = TriggerLevel.valueOf(triggerLevelStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                logger.warn("Invalid trigger level: " + triggerLevelStr + ", using default RED");
                this.triggerLevel = TriggerLevel.RED;
            }
            return this;
        }
        
        public Builder preRecordSeconds(int preRecordSeconds) {
            this.preRecordSeconds = preRecordSeconds;
            return this;
        }
        
        public Builder postRecordSeconds(int postRecordSeconds) {
            this.postRecordSeconds = postRecordSeconds;
            return this;
        }

        public Builder monitorFps(int monitorFps) {
            this.monitorFps = monitorFps;
            return this;
        }

        public Builder monitorBitrate(int monitorBitrate) {
            this.monitorBitrate = monitorBitrate;
            return this;
        }

        public Builder eventBitrate(int eventBitrate) {
            this.eventBitrate = eventBitrate;
            return this;
        }

        public Builder lowPowerMonitor(boolean lowPowerMonitor) {
            this.lowPowerMonitor = lowPowerMonitor;
            return this;
        }

        public ProximityGuardConfig build() {
            return new ProximityGuardConfig(this);
        }
    }
    
    // ==================== FACTORY METHODS ====================
    
    /**
     * Create default configuration.
     */
    public static ProximityGuardConfig createDefault() {
        return new Builder().build();
    }
    
    /**
     * Create configuration from UnifiedConfigManager.
     */
    public static ProximityGuardConfig fromConfig(org.json.JSONObject config) {
        Builder builder = new Builder();
        
        if (config.has("enabled")) {
            builder.enabled(config.optBoolean("enabled", false));
        }
        
        if (config.has("triggerLevel")) {
            builder.triggerLevel(config.optString("triggerLevel", "RED"));
        }
        
        if (config.has("preRecordSeconds")) {
            builder.preRecordSeconds(config.optInt("preRecordSeconds", 5));
        }
        
        if (config.has("postRecordSeconds")) {
            builder.postRecordSeconds(config.optInt("postRecordSeconds", 10));
        }

        if (config.has("monitorFps")) {
            builder.monitorFps(config.optInt("monitorFps", DEFAULT_MONITOR_FPS));
        }

        if (config.has("monitorBitrate")) {
            builder.monitorBitrate(config.optInt("monitorBitrate", DEFAULT_MONITOR_BITRATE));
        }

        if (config.has("eventBitrate")) {
            builder.eventBitrate(config.optInt("eventBitrate", DEFAULT_EVENT_BITRATE));
        }

        if (config.has("lowPowerMonitor")) {
            builder.lowPowerMonitor(config.optBoolean("lowPowerMonitor", true));
        }

        return builder.build();
    }

    // ==================== UTILITY ====================

    @Override
    public String toString() {
        return "ProximityGuardConfig{" +
                "enabled=" + enabled +
                ", triggerLevel=" + triggerLevel +
                ", preRecordSeconds=" + preRecordSeconds +
                ", postRecordSeconds=" + postRecordSeconds +
                ", lowPowerMonitor=" + lowPowerMonitor +
                ", monitorFps=" + monitorFps +
                ", monitorBitrate=" + (monitorBitrate / 1_000_000.0) + "Mbps" +
                ", eventBitrate=" + (eventBitrate / 1_000_000.0) + "Mbps" +
                '}';
    }
    
    /**
     * Get validation bounds for UI.
     */
    public static int getMinPreRecordSeconds() {
        return MIN_PRE_RECORD_SECONDS;
    }
    
    public static int getMaxPreRecordSeconds() {
        return MAX_PRE_RECORD_SECONDS;
    }
    
    public static int getMinPostRecordSeconds() {
        return MIN_POST_RECORD_SECONDS;
    }
    
    public static int getMaxPostRecordSeconds() {
        return MAX_POST_RECORD_SECONDS;
    }
}

package com.overdrive.app.telegram.event;

import androidx.annotation.Nullable;
import com.overdrive.app.telegram.TelegramMessages;

/**
 * Event emitted when a surveillance recording is finalized.
 */
public class VideoEvent extends SystemEvent {
    private final String filePath;
    @Nullable private final String aiDetection;  // e.g., "person", "car"
    private final int durationSeconds;
    
    public VideoEvent(String filePath, @Nullable String aiDetection, int durationSeconds) {
        super(EventType.VIDEO);
        this.filePath = filePath;
        this.aiDetection = aiDetection;
        this.durationSeconds = durationSeconds;
    }
    
    public String getFilePath() { return filePath; }
    @Nullable public String getAiDetection() { return aiDetection; }
    public int getDurationSeconds() { return durationSeconds; }
    
    @Override
    public String getMessage() {
        if (aiDetection != null) {
            return TelegramMessages.get("legacy.video.saved_with_detection",
                    localizedDetection(aiDetection), String.valueOf(durationSeconds));
        }
        return TelegramMessages.get("legacy.video.saved",
                String.valueOf(durationSeconds));
    }

    private String localizedDetection(String value) {
        switch (value.toLowerCase(java.util.Locale.ROOT)) {
            case "person": return TelegramMessages.get("recording_label.person");
            case "bike":
            case "bicycle": return TelegramMessages.get("recording_label.bike");
            case "car":
            case "vehicle": return TelegramMessages.get("recording_label.vehicle");
            case "animal": return TelegramMessages.get("recording_label.animal");
            case "motion": return TelegramMessages.get("recording_label.motion");
            default: return value;
        }
    }
}

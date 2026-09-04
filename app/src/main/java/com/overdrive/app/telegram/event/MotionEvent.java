package com.overdrive.app.telegram.event;

import androidx.annotation.Nullable;
import com.overdrive.app.telegram.TelegramMessages;

/**
 * Event emitted when motion is detected.
 */
public class MotionEvent extends SystemEvent {
    @Nullable private final String aiDetection;  // e.g., "person", "car", null for generic motion
    private final float confidence;
    
    public MotionEvent(@Nullable String aiDetection, float confidence) {
        super(EventType.MOTION);
        this.aiDetection = aiDetection;
        this.confidence = confidence;
    }
    
    @Nullable public String getAiDetection() { return aiDetection; }
    public float getConfidence() { return confidence; }
    
    @Override
    public String getMessage() {
        if (aiDetection != null) {
            return TelegramMessages.get("legacy.motion.detected_actor",
                    localizedDetection(aiDetection),
                    String.valueOf(Math.round(confidence * 100)));
        } else {
            return TelegramMessages.get("legacy.motion.detected");
        }
    }
    
    private String localizedDetection(String value) {
        switch (value.toLowerCase(java.util.Locale.ROOT)) {
            case "person": return TelegramMessages.get("motion.actor.person");
            case "bike":
            case "bicycle": return TelegramMessages.get("motion.actor.bike");
            case "car":
            case "vehicle": return TelegramMessages.get("motion.actor.vehicle");
            case "animal": return TelegramMessages.get("motion.actor.animal");
            case "motion": return TelegramMessages.get("motion.actor.motion");
            default: return value;
        }
    }
}

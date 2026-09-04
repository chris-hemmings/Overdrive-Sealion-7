package com.overdrive.app.telegram.event;

import com.overdrive.app.telegram.TelegramMessages;

/**
 * Critical system event requiring immediate attention.
 */
public class CriticalEvent extends SystemEvent {
    private final CriticalType criticalType;
    private final String details;
    
    public enum CriticalType {
        LOW_BATTERY("critical.type.low_battery"),
        STORAGE_FULL("critical.type.storage_full"),
        DAEMON_CRASH("critical.type.daemon_crash"),
        SYSTEM_ERROR("critical.type.system_error"),
        SYSTEM_REBOOT("critical.type.system_reboot");
        
        private final String messageKey;
        CriticalType(String messageKey) { this.messageKey = messageKey; }
        public String getPrefix() { return TelegramMessages.get(messageKey); }
    }
    
    public CriticalEvent(CriticalType criticalType, String details) {
        super(EventType.CRITICAL);
        this.criticalType = criticalType;
        this.details = details;
    }
    
    public CriticalType getCriticalType() { return criticalType; }
    public String getDetails() { return details; }
    
    @Override
    public String getMessage() {
        return TelegramMessages.get("legacy.critical_with_details",
                criticalType.getPrefix(), details);
    }
}

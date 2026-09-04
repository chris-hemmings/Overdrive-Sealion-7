package com.overdrive.app.telegram.event;

import com.overdrive.app.telegram.TelegramMessages;

/**
 * Event emitted when Cloudflare tunnel URL is created or changed.
 */
public class TunnelEvent extends SystemEvent {
    private final String url;
    private final boolean isNew;  // true if new tunnel, false if URL changed
    
    public TunnelEvent(String url, boolean isNew) {
        super(EventType.TUNNEL);
        this.url = url;
        this.isNew = isNew;
    }
    
    public String getUrl() { return url; }
    public boolean isNew() { return isNew; }
    
    @Override
    public String getMessage() {
        return TelegramMessages.get(isNew
                ? "legacy.tunnel.connected" : "legacy.tunnel.changed", url);
    }
}

package com.overdrive.app.camera;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Named subview extracted from a panoramic strip.
 */
public enum CameraVirtualView {
    FRONT("front", "360 Front", 1, 0.75f),
    RIGHT("right", "360 Right", 2, 0.50f),
    REAR("rear", "360 Rear", 3, 0.00f),
    LEFT("left", "360 Left", 4, 0.25f);

    private final String id;
    private final String displayName;
    private final int streamViewMode;
    private final float stripOffsetX;

    CameraVirtualView(String id, String displayName, int streamViewMode, float stripOffsetX) {
        this.id = id;
        this.displayName = displayName;
        this.streamViewMode = streamViewMode;
        this.stripOffsetX = stripOffsetX;
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public int getStreamViewMode() {
        return streamViewMode;
    }

    public float getStripOffsetX() {
        return stripOffsetX;
    }

    public JSONObject toJson() {
        JSONObject out = new JSONObject();
        putSafely(out, "id", id);
        putSafely(out, "label", displayName);
        putSafely(out, "streamViewMode", streamViewMode);
        return out;
    }

    private static void putSafely(JSONObject object, String key, Object value) {
        try {
            object.put(key, value);
        } catch (JSONException e) {
            throw new IllegalStateException("Failed to write JSON field '" + key + "'", e);
        }
    }

    public static CameraVirtualView fromId(String id) {
        if (id == null) return null;
        for (CameraVirtualView value : values()) {
            if (value.id.equalsIgnoreCase(id)) {
                return value;
            }
        }
        return null;
    }
}

package com.overdrive.app.camera;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Logical camera role presented to the user.
 */
public enum CameraRole {
    WINDSHIELD("windshield", "Windshield"),
    CABIN("cabin", "Cabin"),
    PANO_FRONT("panoFront", "360 Front"),
    PANO_REAR("panoRear", "360 Rear"),
    PANO_LEFT("panoLeft", "360 Left"),
    PANO_RIGHT("panoRight", "360 Right");

    private final String key;
    private final String displayName;

    CameraRole(String key, String displayName) {
        this.key = key;
        this.displayName = displayName;
    }

    public String getKey() {
        return key;
    }

    public String getDisplayName() {
        return displayName;
    }

    public JSONObject toJson() {
        JSONObject out = new JSONObject();
        putSafely(out, "key", key);
        putSafely(out, "label", displayName);
        return out;
    }

    private static void putSafely(JSONObject object, String key, Object value) {
        try {
            object.put(key, value);
        } catch (JSONException e) {
            throw new IllegalStateException("Failed to write JSON field '" + key + "'", e);
        }
    }

    public static CameraRole fromKey(String key) {
        if (key == null) return null;
        for (CameraRole role : values()) {
            if (role.key.equalsIgnoreCase(key)) {
                return role;
            }
        }
        return null;
    }
}

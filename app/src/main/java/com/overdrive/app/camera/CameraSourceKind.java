package com.overdrive.app.camera;

/**
 * Backing source used for a camera role.
 */
public enum CameraSourceKind {
    DIRECT("direct"),
    PANORAMIC_SLICE("panoramicSlice"),
    PANORAMIC_VIRTUAL("panoramicVirtual");

    private final String id;

    CameraSourceKind(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }

    public static CameraSourceKind fromId(String id) {
        if (id == null) return null;
        for (CameraSourceKind value : values()) {
            if (value.id.equalsIgnoreCase(id)) {
                return value;
            }
        }
        return null;
    }
}

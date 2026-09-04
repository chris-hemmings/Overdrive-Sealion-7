package com.overdrive.app.camera;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Serializable pointer to a direct camera or a panoramic subview.
 */
public final class CameraSourceRef {
    private final CameraSourceKind kind;
    private final Integer cameraId;
    private final PanoramicSlice panoramicSlice;

    private CameraSourceRef(CameraSourceKind kind, Integer cameraId, PanoramicSlice panoramicSlice) {
        this.kind = kind;
        this.cameraId = cameraId;
        this.panoramicSlice = panoramicSlice;
    }

    public static CameraSourceRef direct(int cameraId) {
        return new CameraSourceRef(CameraSourceKind.DIRECT, cameraId, null);
    }

    public static CameraSourceRef panoramicSlice(PanoramicSlice panoramicSlice) {
        return new CameraSourceRef(CameraSourceKind.PANORAMIC_SLICE, null, panoramicSlice);
    }

    public CameraSourceKind getKind() {
        return kind;
    }

    public Integer getCameraId() {
        return cameraId;
    }

    public PanoramicSlice getPanoramicSlice() {
        return panoramicSlice;
    }

    public String getStableId() {
        if (kind == CameraSourceKind.DIRECT) {
            return "direct:" + cameraId;
        }
        return "panoSlice:" + (panoramicSlice != null ? panoramicSlice.getId() : "unknown");
    }

    public String getDisplayLabel() {
        if (kind == CameraSourceKind.DIRECT) {
            return "Direct camera " + cameraId;
        }
        return panoramicSlice != null ? panoramicSlice.getDisplayName() : "Merged panoramic slice";
    }

    public JSONObject toJson() {
        JSONObject out = new JSONObject();
        putSafely(out, "kind", kind.getId());
        if (cameraId != null) {
            putSafely(out, "cameraId", cameraId.intValue());
        }
        if (panoramicSlice != null) {
            putSafely(out, "slice", panoramicSlice.getId());
        }
        putSafely(out, "id", getStableId());
        putSafely(out, "label", getDisplayLabel());
        return out;
    }

    private static void putSafely(JSONObject object, String key, Object value) {
        try {
            object.put(key, value);
        } catch (JSONException e) {
            throw new IllegalStateException("Failed to write JSON field '" + key + "'", e);
        }
    }

    public static CameraSourceRef fromJson(JSONObject obj) {
        if (obj == null) return null;
        CameraSourceKind kind = CameraSourceKind.fromId(obj.optString("kind", null));
        if (kind == CameraSourceKind.DIRECT && obj.has("cameraId")) {
            int id = obj.optInt("cameraId", -1);
            // Range-validate at construction time — a malformed payload
            // sending cameraId=-1 / 99 / etc. used to slip through and
            // persist garbage in unified config that no preview path
            // could ever satisfy. BYD panoramic platform enumerates
            // physical cameras 0..5.
            if (id < 0 || id > 5) return null;
            return direct(id);
        }
        if (kind == CameraSourceKind.PANORAMIC_SLICE) {
            PanoramicSlice slice = PanoramicSlice.fromId(obj.optString("slice", null));
            if (slice != null) {
                return panoramicSlice(slice);
            }
        }
        if (kind == CameraSourceKind.PANORAMIC_VIRTUAL) {
            CameraVirtualView view = CameraVirtualView.fromId(obj.optString("view", null));
            PanoramicSlice slice = PanoramicSlice.fromLegacyView(view);
            if (slice != null) {
                return panoramicSlice(slice);
            }
        }
        return null;
    }
}

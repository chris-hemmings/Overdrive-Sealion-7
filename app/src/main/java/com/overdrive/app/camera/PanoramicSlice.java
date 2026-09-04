package com.overdrive.app.camera;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Raw quarter-slice from the merged panoramic strip.
 *
 * Neutral physical slices, not logical directions. Logical front/right/rear/left
 * is derived by mapping camera roles to slices.
 */
public enum PanoramicSlice {
    // stripOffsetX is the slice's left-edge in a 5120-wide 4-strip (legacy
    // pano_h/pano_l HAL).
    // cornerX / cornerY are the slice's top-left in a 2x2 mosaic the
    // dilink4 HAL emits natively. Grid positions match the recorder's
    // shader layout (TL/TR/BL/BR fragment branches in
    // GpuMosaicRecorder.buildFragmentShader's `gridPos` math).
    //
    // Slice → corner mapping: legacy_seal_atto profile maps
    //   PANO_FRONT → SLICE_4, RIGHT → SLICE_3, REAR → SLICE_1, LEFT → SLICE_2
    // and the recorder paints those four into 2x2 corners in
    //   FRONT=TL, RIGHT=TR, REAR=BL, LEFT=BR
    // so each slice carries the corner the recorder paints it into.
    SLICE_1("slice1", "Merged slice 1", 0, 0.00f, 0.00f, 0.50f),  // BL → REAR
    SLICE_2("slice2", "Merged slice 2", 1, 0.25f, 0.50f, 0.50f),  // BR → LEFT
    SLICE_3("slice3", "Merged slice 3", 2, 0.50f, 0.50f, 0.00f),  // TR → RIGHT
    SLICE_4("slice4", "Merged slice 4", 3, 0.75f, 0.00f, 0.00f);  // TL → FRONT

    private final String id;
    private final String displayName;
    private final int index;
    private final float stripOffsetX;
    private final float cornerX;
    private final float cornerY;

    PanoramicSlice(String id, String displayName, int index, float stripOffsetX,
                   float cornerX, float cornerY) {
        this.id = id;
        this.displayName = displayName;
        this.index = index;
        this.stripOffsetX = stripOffsetX;
        this.cornerX = cornerX;
        this.cornerY = cornerY;
    }

    /** Top-left X of this slice's 0.5×0.5 corner in a 2x2-native HAL frame. */
    public float getCornerX() { return cornerX; }
    /** Top-left Y of this slice's 0.5×0.5 corner in a 2x2-native HAL frame. */
    public float getCornerY() { return cornerY; }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public int getIndex() {
        return index;
    }

    public float getStripOffsetX() {
        return stripOffsetX;
    }

    public JSONObject toJson() {
        JSONObject out = new JSONObject();
        putSafely(out, "id", id);
        putSafely(out, "label", displayName);
        putSafely(out, "index", index + 1);
        putSafely(out, "offsetX", stripOffsetX);
        return out;
    }

    public static PanoramicSlice fromId(String id) {
        if (id == null) return null;
        for (PanoramicSlice value : values()) {
            if (value.id.equalsIgnoreCase(id)) {
                return value;
            }
        }
        return null;
    }

    public static PanoramicSlice fromLegacyView(CameraVirtualView view) {
        if (view == null) return null;
        switch (view) {
            case REAR:  return SLICE_1;
            case LEFT:  return SLICE_2;
            case RIGHT: return SLICE_3;
            case FRONT:
            default:    return SLICE_4;
        }
    }

    private static void putSafely(JSONObject object, String key, Object value) {
        try {
            object.put(key, value);
        } catch (JSONException e) {
            throw new IllegalStateException("Failed to write JSON field '" + key + "'", e);
        }
    }
}

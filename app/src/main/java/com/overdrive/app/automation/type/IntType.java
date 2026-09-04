package com.overdrive.app.automation.type;

import com.overdrive.app.automation.value.IntValue;
import com.overdrive.app.automation.value.Label;

import org.json.JSONObject;

public class IntType extends BaseType<Integer> {
    private static final String TYPE = "int";
    private final Label label;

    private final int min;
    private final int max;

    /**
     * An int representation
     * Will take a min and max value to restrict the possible options
     *
     * @param label An id and display name for this int
     * @param min   The minimum value this int can be (inclusive)
     * @param max   The maximum value this int can be (inclusive)
     */
    public IntType(Label label, int min, int max) {
        this.label = label;
        this.min = min;
        this.max = max;
    }

    /**
     * The label that was stored when this int was initialized
     *
     * @return The Label for this int
     */
    public Label getLabel() {
        return label;
    }

    /**
     * The minimum value this int can be (inclusive)
     *
     * @return The minimum value this int can be (inclusive)
     */
    public int getMin() {
        return min;
    }

    /**
     * The maximum value this int can be (inclusive)
     *
     * @return The maximum value this int can be (inclusive)
     */
    public int getMax() {
        return max;
    }

    /**
     * The comparators for this int
     *
     * @return The comparators for this int
     */
    public EnumType getComparators() {
        return IntValue.COMPARATORS;
    }

    /**
     * Check if the value is between the min and max for this type
     *
     * @param value The value to check
     * @return true if valid, false otherwise
     */
    public boolean isValidValue(Integer value) {
        if (value == null) return false;

        return value.compareTo(getMin()) >= 0 && value.compareTo(getMax()) <= 0;
    }

    /**
     * Create a JSON representation of this int to display in the frontend
     * Will contain the min and max values
     *
     * @return JSON representation of this int
     */
    public JSONObject toJson() {
        JSONObject json = getLabel().toJson();

        try {
            json.put("type", TYPE);
            json.put("min", getMin());
            json.put("max", getMax());
        } catch (Exception e) {
            // JSONObject.put only throws on null key
        }

        return json;
    }
}

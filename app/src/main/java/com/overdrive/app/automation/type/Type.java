package com.overdrive.app.automation.type;

import com.overdrive.app.automation.value.Label;

import org.json.JSONObject;

public interface Type {
    /**
     * Get the label of this type with an id and display value
     *
     * @return The label for this type
     */
    Label getLabel();

    /**
     * List of comparators with a label for showing in the frontend.
     * The returned id from this list can be used in the compare function
     *
     * @return List of comparators
     */
    EnumType getComparators();

    /**
     * Check if comparator id is in the list of comparators
     *
     * @param value The value to check
     * @return true if valid, false otherwise
     */
    boolean isValidComparator(String value);

    /**
     * Check if value is valid for a specific type
     *
     * @param value The value to check
     * @return true if valid, false otherwise
     */
    boolean isValid(Object value);

    /**
     * Create a JSON object with the fields required for the frontend to display
     *
     * @return JSON representation of this type
     */
    JSONObject toJson();
}

package com.overdrive.app.automation.value;

import com.overdrive.app.automation.type.EnumType;

public interface Value {
    /**
     * List of comparators with a label for showing in the frontend.
     * The returned id from this list can be used in the compare function.
     * All values should have eq and neq defined and may have extra.
     *
     * @return List of comparators
     */
    EnumType getComparators();

    /**
     * Compare the value to another value using a specific comparator.
     * The other parameter can be a Value type or the base type e.g. String
     * If the comparator is not available for this type, will return null.
     *
     * @param other      The value to compare to this one
     * @param comparator The comparator e.g. `eq` for equals
     * @return The return result of the comparator, null if it can't be compared
     */
    Boolean compare(Object other, String comparator);
}

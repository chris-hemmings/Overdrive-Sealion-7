package com.overdrive.app.automation.value;

import com.overdrive.app.automation.type.EnumType;

public class StringValue extends BaseValue<String> {
    public static final EnumType COMPARATORS = new EnumType(
            new Label("comparator", "Compare"),
            new Label("eq", "Equal To"),
            new Label("neq", "Not Equal To"));
    private final String value;

    /**
     * A string value with methods to compare to other values
     *
     * @param value The string to store
     */
    public StringValue(String value) {
        this.value = value;
    }

    /**
     * The comparators for this string
     *
     * @return The comparators for this string
     */
    public EnumType getComparators() {
        return COMPARATORS;
    }

    /**
     * The string which was stored
     *
     * @return The string which was stored
     */
    public String getValue() {
        return this.value;
    }

    /**
     * Compare the string to another string using a specific comparator
     *
     * @param other      The value to compare to this one
     * @param comparator The comparator e.g. `eq` for equals
     * @return The return result of the comparator, null if it can't be compared
     */
    public Boolean compareValue(String other, String comparator) {
        if (getValue() == null || other == null || comparator == null) return null;

        switch (comparator) {
            case "eq":
                return getValue().equalsIgnoreCase(other);
            case "neq":
                return !getValue().equalsIgnoreCase(other);
            default:
                return null;
        }
    }
}

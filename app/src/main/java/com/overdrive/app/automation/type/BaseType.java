package com.overdrive.app.automation.type;

import com.overdrive.app.automation.value.BaseValue;

public abstract class BaseType<T> implements Type {
    /**
     * Check if comparator id is in the list of comparators
     * Can be overridden if needed
     *
     * @param value The value to check
     * @return true if valid, false otherwise
     */
    public boolean isValidComparator(String value) {
        return getComparators().getOptions().stream().anyMatch(comparator -> comparator.getId().equals(value));
    }

    /**
     * Should return true if the provided value fits the constraints of this type
     *
     * @param value The value to check
     * @return true if valid, false otherwise
     */
    public abstract boolean isValidValue(T value);

    /**
     * Takes an object of any type and checks if it is valid for this type
     * A value of type Value can also be passed in
     * Will validate against the constraints for this type
     *
     * @param value The value to check
     * @return true if valid, false otherwise
     */
    @SuppressWarnings("unchecked")
    public boolean isValid(Object value) {
        if (value instanceof BaseValue<?>) {
            value = ((BaseValue<?>) value).getValue();
        }

        try {
            // Can't check instanceof a generic type so catch exception instead
            return this.isValidValue((T) value);
        } catch (ClassCastException e) {
            return false;
        }
    }
}

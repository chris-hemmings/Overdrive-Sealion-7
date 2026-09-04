package com.overdrive.app.automation.value;

public abstract class BaseValue<T> implements Value {
    /**
     * Get the primitive value stored for this Value
     *
     * @return The value stored
     */
    public abstract T getValue();

    /**
     * Compare another value to the stored value using a comparator
     * See getComparators() to see which comparators are allowed to be used
     * Will return null if it can't be comparared with that comparator
     *
     * @param other      The value to compare to this one
     * @param comparator The comparator e.g. `eq` for equals
     * @return The return result of the comparator, null if it can't be compared
     */
    public abstract Boolean compareValue(T other, String comparator);

    /**
     * Compare the value to another value using a specific comparator.
     * The other parameter can be a Value type or the base type e.g. String
     * If the comparator is not available for this type, will return null.
     *
     * @param other      The value to compare to this one
     * @param comparator The comparator e.g. `eq` for equals
     * @return The return result of the comparator, null if it can't be compared
     */
    @SuppressWarnings("unchecked")
    public Boolean compare(Object other, String comparator) {
        if (other instanceof BaseValue<?>) {
            other = ((BaseValue<?>) other).getValue();
        }

        try {
            // Can't check instanceof a generic type so catch exception instead
            return this.compareValue((T) other, comparator);
        } catch (ClassCastException e) {
            return null;
        }
    }

    public String toString() {
        return getValue().toString();
    }
}

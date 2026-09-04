package com.overdrive.app.automation.condition;

import org.json.JSONObject;

import java.util.Map;
import java.util.Objects;

public class EventData {
    private final String type;
    private final Map<String, String> variables;

    /**
     * Create an EventData instance to reference specific events
     * This class implements the equals and hashcode methods to allow it to be used as a key for a map
     *
     * @param type      The condition id representing the event
     * @param variables Any extra variables needed to differentiate the type
     */
    public EventData(String type, Map<String, String> variables) {
        this.type = type;
        this.variables = variables;
    }

    /**
     * A constructor to create an instance of this class with no variables
     *
     * @param type The condition id representing the event
     */
    public EventData(String type) {
        this.type = type;
        this.variables = Map.of();
    }

    /**
     * Return the type stored for this EventData
     *
     * @return The condition id representing the event
     */
    public String getType() {
        return type;
    }

    /**
     * Return the extra variables stored for this EventData
     *
     * @return Any extra variables needed to differentiate the type
     */
    public Map<String, String> getVariables() {
        return variables;
    }

    /**
     * Compare 2 EventData types
     * They will be equal if they are referencing the same event with the same variables
     *
     * @param o The other EventData to compare
     * @return true if they are equal in value
     */
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof EventData)) return false;
        EventData other = (EventData) o;
        return Objects.equals(getType(), other.getType()) &&
                Objects.equals(getVariables(), other.getVariables());
    }

    /**
     * HashCode for the event type
     * Represents both the type and variables so it can be safely used as the key for a map
     *
     * @return Hashcode for this EventData
     */
    public int hashCode() {
        return Objects.hash(getType(), getVariables());
    }

    /**
     * Create a JSON object which can be stored
     *
     * @return JSON representation of this EventData
     */
    public JSONObject toJson() {
        JSONObject json = new JSONObject();

        try {
            json.put("type", getType());
            JSONObject variables = new JSONObject();
            for (Map.Entry<String, String> variable : getVariables().entrySet()) {
                variables.put(variable.getKey(), variable.getValue());
            }
            json.put("variables", variables);
        } catch (Exception e) {
            // JSONObject.put only throws on null key
        }

        return json;
    }
}

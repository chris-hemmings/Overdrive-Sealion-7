package com.overdrive.app.automation.value;

import com.overdrive.app.server.Messages;

import org.json.JSONObject;

import java.util.Objects;

public class Label {
    private final String id;
    private final String label;

    /**
     * A label representation which contains an id and label
     * The label provided must be contained within the language files
     *
     * @param id    The id for this label
     * @param label The Language id for this label
     */
    public Label(String id, String label) {
        this.id = id;
        this.label = label;
    }

    /**
     * A string id for this label
     *
     * @return id for this label
     */
    public String getId() {
        return id;
    }

    /**
     * Human-readable label
     * Will be translated using the language files
     *
     * @return The label stored for the frontend
     */
    public String getLabel() {
        return Messages.get(label);
    }

    /**
     * Create a JSON object with the fields required for the frontend to display
     *
     * @return JSON representation of this label
     */
    public JSONObject toJson() {
        JSONObject json = new JSONObject();

        try {
            json.put("id", getId());
            json.put("label", getLabel());
        } catch (Exception e) {
            // JSONObject.put only throws on null key
        }

        return json;
    }

    /**
     * Hashcode for this label
     * Will only compare the id as the label is only used for display
     *
     * @return The hashcode of the label id
     */
    public int hashCode() {
        return Objects.hash(getId());
    }

    /**
     * Check if 2 labels are equal
     * Will only compare the id as the label is only used for display
     *
     * @param o The other Label to compare
     * @return true if they are equal in value
     */
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Label)) return false;
        return Objects.equals(getId(), ((Label) o).getId());
    }
}

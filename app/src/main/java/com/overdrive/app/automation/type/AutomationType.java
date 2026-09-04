package com.overdrive.app.automation.type;

import com.overdrive.app.automation.value.Label;
import com.overdrive.app.automation.value.StringValue;

import org.json.JSONObject;

/**
 * A target-automation selector for the "Control Automation" action. The value is an
 * automation id (UUID) stored as a string; the frontend renders a live dropdown from
 * {@code GET /api/automations/picker} showing each automation's name (or a generated
 * fallback). Mirrors {@link ActionGroupType} / {@link AudioType} — the option set is
 * live, not baked into the schema, since automations change per device.
 *
 * <p>Reuses {@link StringValue} as its backing value. The daemon resolves the id at
 * fire time and no-ops on an unknown/deleted target.
 */
public class AutomationType extends BaseType<String> {
    private static final String TYPE = "automationRef";
    private final Label label;

    public AutomationType(Label label) {
        this.label = label;
    }

    public Label getLabel() {
        return label;
    }

    /** ACTION-only selector; comparators never requested. */
    public EnumType getComparators() {
        return StringValue.COMPARATORS;
    }

    /** A UUID-ish id: non-empty, bounded, hex + hyphen + token charset. Syntactic only —
     *  the daemon checks the id actually resolves to an automation at fire time. */
    public boolean isValidValue(String value) {
        if (value == null || value.isEmpty() || value.length() > 64) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_';
            if (!ok) return false;
        }
        return true;
    }

    public JSONObject toJson() {
        JSONObject json = getLabel().toJson();
        try {
            json.put("type", TYPE);
        } catch (Exception e) {
            // JSONObject.put only throws on null key
        }
        return json;
    }
}

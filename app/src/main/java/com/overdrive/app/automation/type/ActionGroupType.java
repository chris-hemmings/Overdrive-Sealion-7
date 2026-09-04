package com.overdrive.app.automation.type;

import com.overdrive.app.automation.value.Label;
import com.overdrive.app.automation.value.StringValue;

import org.json.JSONObject;

/**
 * A reusable-action-group selector. The value is a group id (a UUID) stored as a
 * string. The frontend renders it as a dropdown populated live from
 * {@code GET /api/action-groups/list}, so — unlike {@link EnumType} — the option set
 * is NOT baked into the schema (the user's saved groups differ per device and change
 * over time). Mirrors {@link AudioType} / {@link AppType}, which do the same for
 * uploaded sounds and installed apps.
 *
 * <p>Reuses {@link StringValue} as its backing value, so no new Value type or
 * {@code Automations.update} overload is needed. The stored id is resolved to its
 * actions at run time by {@code ActionGroupAction} (call-by-reference).
 */
public class ActionGroupType extends BaseType<String> {
    private static final String TYPE = "actionGroup";
    private final Label label;

    public ActionGroupType(Label label) {
        this.label = label;
    }

    public Label getLabel() {
        return label;
    }

    /** ACTION-only selector (never a condition), so comparators are never requested;
     *  return String comparators defensively rather than null. */
    public EnumType getComparators() {
        return StringValue.COMPARATORS;
    }

    /**
     * A group id is valid if it's a non-empty, bounded string in the id charset. Group
     * ids are UUIDs (hex + hyphens); allow that plus the plain token charset so a
     * hand-edited config can't smuggle a quote that breaks the {@code {"groupId":"…"}}
     * JSON body. The daemon additionally checks the id actually resolves to a group at
     * fire time (unknown id → the action no-ops), so validity here is purely syntactic.
     */
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

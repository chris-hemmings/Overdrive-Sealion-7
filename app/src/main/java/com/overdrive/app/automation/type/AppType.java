package com.overdrive.app.automation.type;

import com.overdrive.app.automation.value.Label;
import com.overdrive.app.automation.value.StringValue;

import org.json.JSONObject;

/**
 * An installed-app selector. The value is a package name (String). The frontend
 * renders it as a dropdown populated live from {@code GET /api/apps/list}, so —
 * unlike {@link EnumType} — the option set is NOT baked into the schema (the
 * installed apps differ per device and change over time).
 *
 * <p>Reuses {@link StringValue} as its backing value so no new Value type or
 * {@code Automations.update} overload is needed (mirrors how ColourType/TimeType
 * reuse IntValue).
 */
public class AppType extends BaseType<String> {
    private static final String TYPE = "app";
    private final Label label;

    /**
     * @param label An id and display name for this app selector
     */
    public AppType(Label label) {
        this.label = label;
    }

    public Label getLabel() {
        return label;
    }

    /**
     * Comparators. Like ColourType this is an ACTION-only variable (a package
     * selection), never a condition, so comparators are never requested — actions
     * call isValid() only. Return String comparators defensively rather than null.
     */
    public EnumType getComparators() {
        return StringValue.COMPARATORS;
    }

    /**
     * A package name is valid if it is a non-empty, bounded string matching the
     * Android package-name charset ([A-Za-z0-9._]). Restricting the charset here
     * is defensive: the value is later substituted into the JSON body
     * {@code {"package":"<value>"}} of the /api/apps/launch action, and a
     * hand-crafted / imported automation could otherwise smuggle a {@code "} that
     * breaks or injects into that JSON. The picker only ever offers real packages,
     * so this never rejects a legitimately-chosen app.
     */
    public boolean isValidValue(String value) {
        if (value == null || value.isEmpty() || value.length() > 255) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '_';
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

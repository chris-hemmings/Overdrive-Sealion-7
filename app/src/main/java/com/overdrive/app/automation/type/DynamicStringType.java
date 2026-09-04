package com.overdrive.app.automation.type;

import com.overdrive.app.automation.value.Label;

/**
 * A {@link StringType} whose value may ALSO be a dynamic reference token
 * ({@code ${signal:TYPE[:k=v]}} or {@code ${var:NAME}}). Used for the "Set Variable"
 * action's value field so a user can set a variable to a LIVE signal / another variable
 * ("Set variable from state" — e.g. {@code DRL_State = ${signal:drl}}), which
 * {@link com.overdrive.app.automation.action.SetVariableAction} resolves to the current
 * value at fire time.
 *
 * <p>Validation is unchanged from the base string type (any string up to the max length
 * is valid — a {@code ${…}} token is just a string), so this is purely a marker: it emits
 * {@code "dynamic": true} in its schema JSON, which the web editor keys off to layer the
 * Value / Signal picker on top of the plain text input for this field. A plain constant
 * string round-trips and fires exactly as before.
 */
public class DynamicStringType extends StringType {

    public DynamicStringType(Label label, int maxLength) {
        super(label, maxLength);
    }

    @Override
    public org.json.JSONObject toJson() {
        org.json.JSONObject json = super.toJson();
        try { json.put("dynamic", true); } catch (Exception ignored) { }
        return json;
    }
}

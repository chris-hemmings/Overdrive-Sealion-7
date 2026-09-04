package com.overdrive.app.automation;

import org.json.JSONObject;

import java.util.Map;

public class AutomationAction {
    private final String type;
    private final Map<String, Object> variables;
    // Optional nested action lists for control-flow actions (loop body, if/else
    // branches). NULL/empty for every ordinary action, and toJson emits these keys
    // ONLY when non-empty — so a flat action serializes byte-identically to before this
    // feature and every pre-existing automation is untouched.
    private final java.util.List<AutomationAction> childActions;
    private final java.util.List<AutomationAction> elseChildActions;

    /**
     * An action representation for a specific automation
     * The variables can have any type as they used within the trigger for an action
     *
     * @param type      The id of the action this is used for
     * @param variables The variables that are needed to run the action
     */
    public AutomationAction(String type, Map<String, Object> variables) {
        this(type, variables, null, null);
    }

    /**
     * Full form carrying optional nested child action lists (for loops / inline
     * conditionals). The 2-arg constructor delegates here with no children, so every
     * existing caller is unchanged.
     *
     * @param childActions     the primary nested actions (loop body / if-true), or null
     * @param elseChildActions the else-branch nested actions (if-false), or null
     */
    public AutomationAction(String type, Map<String, Object> variables,
                            java.util.List<AutomationAction> childActions,
                            java.util.List<AutomationAction> elseChildActions) {
        this.type = type;
        this.variables = variables;
        this.childActions = childActions;
        this.elseChildActions = elseChildActions;
    }

    /** Primary nested actions (loop body / if-true branch). Never null; empty when none. */
    public java.util.List<AutomationAction> getChildActions() {
        return childActions == null ? java.util.List.of() : childActions;
    }

    /** Else-branch nested actions (if-false). Never null; empty when none. */
    public java.util.List<AutomationAction> getElseChildActions() {
        return elseChildActions == null ? java.util.List.of() : elseChildActions;
    }

    /** A copy of this action with the given nested child lists attached. */
    public AutomationAction withChildren(java.util.List<AutomationAction> children,
                                         java.util.List<AutomationAction> elseChildren) {
        return new AutomationAction(type, variables, children, elseChildren);
    }

    /**
     * The id of the action this is used for
     *
     * @return The id of the action this is used for
     */
    public String getType() {
        return type;
    }

    /**
     * The variables that are needed to run the action
     *
     * @return The variables that are needed to run the action
     */
    public Map<String, Object> getVariables() {
        return variables;
    }

    /**
     * Create a JSON object which can be stored and loaded for this action
     *
     * @return JSON representation of this action
     */
    public JSONObject toJson() {
        JSONObject json = new JSONObject();

        try {
            json.put("type", getType());
            JSONObject variables = new JSONObject();
            for (Map.Entry<String, Object> variable : getVariables().entrySet()) {
                variables.put(variable.getKey(), variable.getValue());
            }
            json.put("variables", variables);
            // Nested child lists — emitted ONLY when non-empty, so a flat action's JSON
            // is the identical 2-key {type,variables} object it always was (no new key
            // appears for any pre-existing automation). Recurses via child toJson().
            if (childActions != null && !childActions.isEmpty()) {
                org.json.JSONArray arr = new org.json.JSONArray();
                for (AutomationAction c : childActions) arr.put(c.toJson());
                json.put("childActions", arr);
            }
            if (elseChildActions != null && !elseChildActions.isEmpty()) {
                org.json.JSONArray arr = new org.json.JSONArray();
                for (AutomationAction c : elseChildActions) arr.put(c.toJson());
                json.put("elseActions", arr);
            }
        } catch (Exception e) {
            // JSONObject.put only throws on null key
        }

        return json;
    }
}

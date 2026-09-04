package com.overdrive.app.automation.type;

import com.overdrive.app.automation.AutomationCondition;
import com.overdrive.app.automation.value.Label;

/**
 * An {@link IntType} whose stored value may ALSO be a dynamic reference token
 * ({@code ${var:NAME}} or {@code ${signal:TYPE[:k=v]}}), resolved to a live value at
 * evaluation time. Used for the comparison RHS of the inline flow actions
 * (If / Loop / Wait Until) so a user can compare a signal against a constant OR against
 * a variable / another signal — the same expression-engine capability real conditions
 * already have (see {@link com.overdrive.app.automation.condition.EventCondition
 * #isAcceptableConditionValue}).
 *
 * <p>Validation is additive: a plain integer takes the identical min/max path as the
 * base {@link IntType} (so every existing constant binding is unaffected), and a bounded
 * {@code ${…}} token is accepted as-is (its live value is validated/coerced at compare
 * time by {@link AutomationCondition}). The JSON {@code type} stays {@code "int"} so the
 * web editor renders the same numeric control (with the dynamic value picker layered on
 * top by the condition-value input), and a never-token value round-trips unchanged.
 */
public class DynamicIntType extends IntType {

    public DynamicIntType(Label label, int min, int max) {
        super(label, min, max);
    }

    /**
     * Same JSON as {@link IntType} (so the numeric constant control is unchanged) plus a
     * {@code "dynamic": true} marker. The web editor keys off that marker to layer the
     * Value / Variable / Signal picker on top of the numeric input for THIS field, so the
     * inline flow actions get the same expression-engine RHS a condition has.
     */
    @Override
    public org.json.JSONObject toJson() {
        org.json.JSONObject json = super.toJson();
        try { json.put("dynamic", true); } catch (Exception ignored) { }
        return json;
    }

    /** Max length of a bounded free-text RHS constant (a variable LHS compares as a string,
     *  e.g. {@code Sport_Mode != true}); matches SetVariableAction.MAX_VALUE. */
    private static final int MAX_STRING_RHS = 64;

    @Override
    public boolean isValid(Object value) {
        // Accept a bounded dynamic reference token; anything else falls through to the
        // normal integer range check. Bound the length so a hand-edited/imported config
        // can't store an unbounded string as a "reference" (mirrors EventCondition).
        if (AutomationCondition.isDynamicRef(value)) {
            return ((String) value).length() <= 128;
        }
        // Also accept a bounded plain-STRING constant: an If/Wait-Until/Loop whose LHS is a
        // user variable compares as a string (eq/neq), so the RHS may be a word like
        // "Sport_Mode" that isn't an int. This is additive — a numeric-signal LHS still
        // stores an int from the numeric picker, and the engine's IntValue.compare treats a
        // non-numeric RHS against a numeric signal as "not met" (fail-safe), so accepting a
        // string here can never make an existing numeric comparison wrong.
        if (value instanceof String) {
            return ((String) value).length() <= MAX_STRING_RHS;
        }
        return super.isValid(value);
    }
}

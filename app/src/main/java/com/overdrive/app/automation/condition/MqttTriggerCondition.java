package com.overdrive.app.automation.condition;

import com.overdrive.app.automation.AutomationCondition;
import com.overdrive.app.automation.type.StringType;
import com.overdrive.app.automation.value.Label;

import org.json.JSONObject;

/**
 * A trigger/condition on an INBOUND MQTT channel (see
 * {@link com.overdrive.app.automation.Automations#publishMqttTrigger}).
 *
 * <p>Mirrors {@link VariableCondition}: the differentiating sub-field is a FREE-TEXT
 * {@code channel} the user types (e.g. "front_door", "ha_scene"), not a fixed enum — so
 * this subclass overrides {@link #eventData} / {@link #automationCondition} /
 * {@link #toJson} to carry a {@code channel} string field. The state key it produces
 * ({@code EventData("mqttTrigger",{channel})}) is exactly what the MQTT subscriber writes
 * via {@code publishMqttTrigger}, so an inbound message on {@code <base>/automation/<channel>}
 * lines up with a rule watching that channel. Value comparison is string eq/neq, so
 * "front_door == open" works as written.
 */
public class MqttTriggerCondition extends EventCondition {
    // Same bounded charset the publish seam validates against (Automations.isValidMqttChannel).
    private static final StringType CHANNEL_TYPE =
            new StringType(new Label("channel", "automation.mqtt_channel"), 64);

    public MqttTriggerCondition(Label label, String description) {
        // Value is a free-text string compared with eq/neq. The channel is handled
        // bespoke below rather than through the enum-variable list.
        super(label, description, new StringType(new Label("value", "automation.mqtt_value"), 256));
    }

    /**
     * Build the {@link EventData} keyed by the free-text {@code channel} from
     * {@code variables.channel}, matching {@link com.overdrive.app.automation.condition.BydEvent#mqttTrigger}.
     * Returns null on a missing/blank/invalid channel so a malformed config is rejected.
     */
    @Override
    public EventData eventData(JSONObject input) {
        try {
            JSONObject variablesJson = input.optJSONObject("variables");
            if (variablesJson == null) return null;
            String channel = variablesJson.optString("channel", "").trim();
            if (channel.isEmpty() || !CHANNEL_TYPE.isValidValue(channel)) return null;
            return BydEvent.mqttTrigger(channel);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public AutomationCondition automationCondition(JSONObject input) {
        try {
            EventData eventData = eventData(input);
            if (eventData == null) return null;

            String comparator = input.getString("comparator");
            if (!getValue().isValidComparator(comparator)) return null;

            Object value = input.get("value");
            // Accept a dynamic ${…} reference (resolved live at compare time) or a value
            // that passes this condition's own type validation. Shared base helper.
            if (!isAcceptableConditionValue(value)) return null;

            return new AutomationCondition(eventData, comparator, value);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public JSONObject toJson() {
        JSONObject json = getLabel().toJson();
        try {
            json.put("type", "event");
            org.json.JSONArray variables = new org.json.JSONArray();
            variables.put(CHANNEL_TYPE.toJson());
            json.put("variables", variables);
            json.put("description", getDescription());
            json.put("comparator", getValue().getComparators().toJson());
            json.put("value", getValue().toJson());
        } catch (Exception e) {
            // JSONObject.put only throws on null key
        }
        return json;
    }
}

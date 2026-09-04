package com.overdrive.app.mqtt;

import com.overdrive.app.byd.cloud.crypto.CredentialCipher;

import org.json.JSONObject;

import java.util.UUID;

/**
 * Configuration for a single MQTT broker connection.
 *
 * Each connection is independently configurable with its own broker, topic,
 * credentials, QoS, and publish interval. Multiple connections can run
 * simultaneously, all receiving the same telemetry data.
 *
 * Stored as JSON in /data/local/tmp/mqtt_connections.json.
 */
public class MqttConnectionConfig {

    public String id;                    // UUID, auto-generated
    public String name;                  // User-friendly label ("Home Assistant", "Fleet Server")
    public String brokerUrl;             // tcp://broker.hivemq.com or ssl://your-broker.com
    public int port;                     // 1883 (tcp) or 8883 (ssl)
    public String topic;                 // e.g. overdrive/vehicle/telemetry
    public String clientId;              // Auto-generated from deviceId + connectionId
    public String username;              // Optional MQTT auth
    public String password;              // Optional MQTT auth
    public int qos;                      // 0 = fire-and-forget, 1 = at-least-once
    public boolean enabled;              // Per-connection toggle
    public int publishIntervalSeconds;   // Deprecated — kept for migration (seeds minIntervalSeconds)
    public boolean adaptiveInterval;     // Deprecated — superseded by the min/max window
    public boolean retainMessages;       // MQTT retain flag on published messages (aggregate mode)
    public boolean trustAllCerts;        // If true, accept self-signed/untrusted certs (Home Assistant)

    // Report-by-exception window (the two sliders)
    public int minIntervalSeconds;       // Floor: never publish more often than this
    public int maxIntervalSeconds;       // Heartbeat ceiling: always publish at least this often
    public boolean changeOnly;           // If true, only publish when a backing value changed

    // Per-state heartbeat overrides. When > 0, they replace maxIntervalSeconds (the heartbeat
    // ceiling) while the vehicle is in that state, so telemetry can slow right down when the car
    // is parked (ACC off) — cutting data usage during the ACC-OFF network blackout — and speed up
    // again while charging. 0 = unset = fall back to maxIntervalSeconds. Change-detection still
    // publishes sooner than the ceiling whenever a value actually moves; these only stretch/shrink
    // the "nothing changed" heartbeat cadence per state. Charging takes precedence over parked when
    // both apply (a plugged-in car is parked but the user usually wants livelier charging stats).
    public int parkedIntervalSeconds;    // Heartbeat ceiling while ACC is off (0 = use max)
    public int chargingIntervalSeconds;  // Heartbeat ceiling while charging (0 = use max)

    // Full-resync options. Both re-publish EVERY discoverable key, not just changed ones,
    // so state lost to a connection drop (e.g. a gear→P transition dropped at a WiFi↔cellular
    // handoff) gets corrected — a state-based send profile (full snapshot at transitions).
    // NOTE: in HA mode each key is its own retained topic, so a full sync is one message per
    // discoverable key. At a 1s interval that is heavy; prefer a larger interval.
    public boolean heartbeatSendAll;     // Heartbeat always full-syncs on its own fixed cadence
                                         // (maxIntervalSeconds), independent of intervening
                                         // change-only publishes (no starvation).
    public boolean flushOnStateChange;   // Flush a full snapshot on every mode transition (ACC
                                         // on/off, charging start/stop) so the new state survives
                                         // a publish lost at a network handoff. Light (edges only).

    // Home Assistant discovery
    public boolean homeAssistantDiscovery; // If true: device-bundle discovery + per-field retained topics
    public String discoveryPrefix;         // HA discovery prefix (default "homeassistant")

    // Vehicle control (local SDK/HAL only — never the BYD cloud). When true, HA control
    // entities are discovered and inbound command topics (<base>/<key>/set) are honored.
    public boolean allowControl;           // Master safety toggle (default off)

    // Defaults
    private static final int DEFAULT_PORT = 1883;
    private static final int DEFAULT_QOS = 0;
    private static final int DEFAULT_PUBLISH_INTERVAL = 5;
    private static final boolean DEFAULT_ADAPTIVE = true;
    private static final boolean DEFAULT_RETAIN = false;
    private static final boolean DEFAULT_TRUST_ALL_CERTS = false;
    private static final int DEFAULT_MIN_INTERVAL = 5;
    private static final int DEFAULT_MAX_INTERVAL = 300;
    private static final boolean DEFAULT_CHANGE_ONLY = true;
    private static final boolean DEFAULT_HA_DISCOVERY = false;
    private static final String DEFAULT_DISCOVERY_PREFIX = "homeassistant";
    private static final boolean DEFAULT_ALLOW_CONTROL = false;
    private static final boolean DEFAULT_HEARTBEAT_SEND_ALL = false;
    private static final boolean DEFAULT_FLUSH_ON_STATE_CHANGE = true;
    private static final int DEFAULT_PARKED_INTERVAL = 0;    // 0 = unset → use maxIntervalSeconds
    private static final int DEFAULT_CHARGING_INTERVAL = 0;  // 0 = unset → use maxIntervalSeconds

    /**
     * Create a new connection config with defaults.
     */
    public MqttConnectionConfig() {
        this.id = UUID.randomUUID().toString().substring(0, 8);
        this.name = "";
        this.brokerUrl = "";
        this.port = DEFAULT_PORT;
        this.topic = "overdrive/vehicle/telemetry";
        this.clientId = "";
        this.username = "";
        this.password = "";
        this.qos = DEFAULT_QOS;
        this.enabled = false;
        this.publishIntervalSeconds = DEFAULT_PUBLISH_INTERVAL;
        this.adaptiveInterval = DEFAULT_ADAPTIVE;
        this.retainMessages = DEFAULT_RETAIN;
        this.trustAllCerts = DEFAULT_TRUST_ALL_CERTS;
        this.minIntervalSeconds = DEFAULT_MIN_INTERVAL;
        this.maxIntervalSeconds = DEFAULT_MAX_INTERVAL;
        this.changeOnly = DEFAULT_CHANGE_ONLY;
        this.homeAssistantDiscovery = DEFAULT_HA_DISCOVERY;
        this.discoveryPrefix = DEFAULT_DISCOVERY_PREFIX;
        this.allowControl = DEFAULT_ALLOW_CONTROL;
        this.heartbeatSendAll = DEFAULT_HEARTBEAT_SEND_ALL;
        this.flushOnStateChange = DEFAULT_FLUSH_ON_STATE_CHANGE;
        this.parkedIntervalSeconds = DEFAULT_PARKED_INTERVAL;
        this.chargingIntervalSeconds = DEFAULT_CHARGING_INTERVAL;
    }

    /**
     * Effective heartbeat ceiling (seconds) for the given vehicle state. Charging wins over parked
     * when both are true. A per-state override only applies when it is set (&gt; 0); otherwise the
     * base {@link #maxIntervalSeconds} is used. The result is floored at {@link #minIntervalSeconds}
     * so a mis-set override can never publish faster than the rate floor.
     */
    public int effectiveMaxIntervalSeconds(boolean accOn, boolean charging) {
        int ceiling = maxIntervalSeconds;
        if (charging && chargingIntervalSeconds > 0) {
            ceiling = chargingIntervalSeconds;
        } else if (!accOn && parkedIntervalSeconds > 0) {
            ceiling = parkedIntervalSeconds;
        }
        return Math.max(minIntervalSeconds, ceiling);
    }

    /** True when this connection should accept inbound control commands and discover control entities. */
    public boolean isControlEnabled() {
        return allowControl && homeAssistantDiscovery;
    }

    /** True when this connection should publish HA discovery + per-field retained topics. */
    public boolean isHomeAssistant() {
        return homeAssistantDiscovery;
    }

    /**
     * Build the full broker URI for Paho MQTT client.
     *
     * Supports all Paho URI formats:
     *   tcp://host:port          — plain MQTT
     *   ssl://host:port          — MQTT over TLS
     *   ws://host:port/path      — MQTT over WebSocket
     *   wss://host:port/path     — MQTT over secure WebSocket (the ISP firewall bypass)
     *
     * The regex must allow an optional path after the port (e.g. /mqtt) so that
     * wss://mqtt.eclipseprojects.io:443/mqtt is passed through as-is instead of
     * getting a second port appended.
     */
    public String getBrokerUri() {
        String url = brokerUrl;
        if (url == null || url.isEmpty()) return "";

        // Strip trailing slash (but not path components like /mqtt)
        if (url.endsWith("/")) url = url.substring(0, url.length() - 1);

        // If the URL already contains a port (with or without a trailing path), use as-is.
        // Matches: wss://broker:443  |  wss://broker:443/mqtt  |  ssl://broker:8883
        if (url.matches("^(tcp|ssl|ws|wss)://.*:\\d+(/.*)?$")) {
            return url;
        }

        // Protocol present but no port — append the configured port
        if (url.matches("^(tcp|ssl|ws|wss)://.*")) {
            return url + ":" + port;
        }

        // Bare hostname — prepend tcp:// and append port
        return "tcp://" + url + ":" + port;
    }

    /**
     * Generate a client ID from device ID and connection ID.
     * Falls back to connection ID if device ID is not available.
     */
    public String getEffectiveClientId(String deviceId) {
        if (clientId != null && !clientId.isEmpty()) {
            return clientId;
        }
        if (deviceId != null && !deviceId.isEmpty()) {
            return "overdrive-" + deviceId.substring(0, Math.min(8, deviceId.length())) + "-" + id;
        }
        return "overdrive-" + id;
    }

    /**
     * Check if the broker URI uses a secure protocol (ssl:// or wss://).
     */
    public boolean isSsl() {
        String uri = getBrokerUri();
        return uri.startsWith("ssl://") || uri.startsWith("wss://");
    }

    /**
     * Check if this connection has minimum required configuration.
     */
    public boolean isConfigured() {
        return brokerUrl != null && !brokerUrl.isEmpty()
                && topic != null && !topic.isEmpty();
    }

    /**
     * Returns a masked version of the password for display.
     */
    public String getMaskedPassword() {
        if (password == null || password.isEmpty()) return "";
        if (password.length() <= 2) return "••";
        return "••••" + password.substring(password.length() - 2);
    }

    // ==================== SERIALIZATION ====================

    /**
     * Serialize to JSON for storage.
     *
     * <p>username/password are encrypted at rest via {@link CredentialCipher} —
     * every other field is non-secret config. The in-memory {@link #username}/
     * {@link #password} stay plaintext (MqttPublisherService feeds {@link #password}
     * straight into Paho's {@code setPassword()}); only this on-disk form is protected.
     */
    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("id", id);
            json.put("name", name);
            json.put("brokerUrl", brokerUrl);
            json.put("port", port);
            json.put("topic", topic);
            json.put("clientId", clientId);
            json.put("username", CredentialCipher.encrypt(username));
            json.put("password", CredentialCipher.encrypt(password));
            json.put("qos", qos);
            json.put("enabled", enabled);
            json.put("publishIntervalSeconds", publishIntervalSeconds);
            json.put("adaptiveInterval", adaptiveInterval);
            json.put("retainMessages", retainMessages);
            json.put("trustAllCerts", trustAllCerts);
            json.put("minIntervalSeconds", minIntervalSeconds);
            json.put("maxIntervalSeconds", maxIntervalSeconds);
            json.put("changeOnly", changeOnly);
            json.put("homeAssistantDiscovery", homeAssistantDiscovery);
            json.put("discoveryPrefix", discoveryPrefix);
            json.put("allowControl", allowControl);
            json.put("heartbeatSendAll", heartbeatSendAll);
            json.put("flushOnStateChange", flushOnStateChange);
            json.put("parkedIntervalSeconds", parkedIntervalSeconds);
            json.put("chargingIntervalSeconds", chargingIntervalSeconds);
        } catch (Exception ignored) {}
        return json;
    }

    /**
     * Serialize to JSON for API responses (masks password).
     *
     * <p>toJson() now encrypts username/password for on-disk storage, so both
     * are put back here in their real (API-facing) form: username in the
     * clear — callers display it for reference — and password masked, never
     * echoed even in encrypted form.
     */
    public JSONObject toSafeJson() {
        JSONObject json = toJson();
        try {
            json.put("username", username);
            json.put("password", getMaskedPassword());
            json.put("configured", isConfigured());
        } catch (Exception ignored) {}
        return json;
    }

    /**
     * Deserialize from JSON.
     */
    public static MqttConnectionConfig fromJson(JSONObject json) {
        MqttConnectionConfig config = new MqttConnectionConfig();
        config.id = json.optString("id", config.id);
        config.name = json.optString("name", "");
        config.brokerUrl = json.optString("brokerUrl", "");
        config.port = json.optInt("port", DEFAULT_PORT);
        config.topic = json.optString("topic", "overdrive/vehicle/telemetry");
        config.clientId = json.optString("clientId", "");
        // decrypt() passes plaintext through unchanged (isEncrypted() check),
        // so this also transparently migrates connections saved before
        // username/password were encrypted — the next save() re-encrypts them.
        config.username = CredentialCipher.decrypt(json.optString("username", ""));
        config.password = CredentialCipher.decrypt(json.optString("password", ""));
        config.qos = json.optInt("qos", DEFAULT_QOS);
        config.enabled = json.optBoolean("enabled", false);
        config.publishIntervalSeconds = json.optInt("publishIntervalSeconds", DEFAULT_PUBLISH_INTERVAL);
        config.adaptiveInterval = json.optBoolean("adaptiveInterval", DEFAULT_ADAPTIVE);
        config.retainMessages = json.optBoolean("retainMessages", DEFAULT_RETAIN);
        config.trustAllCerts = json.optBoolean("trustAllCerts", DEFAULT_TRUST_ALL_CERTS);
        // Migrate the old single interval into the new floor if the new fields aren't set yet.
        config.minIntervalSeconds = json.optInt("minIntervalSeconds",
                json.optInt("publishIntervalSeconds", DEFAULT_MIN_INTERVAL));
        config.maxIntervalSeconds = json.optInt("maxIntervalSeconds", DEFAULT_MAX_INTERVAL);
        config.changeOnly = json.optBoolean("changeOnly", DEFAULT_CHANGE_ONLY);
        config.homeAssistantDiscovery = json.optBoolean("homeAssistantDiscovery", DEFAULT_HA_DISCOVERY);
        config.discoveryPrefix = json.optString("discoveryPrefix", DEFAULT_DISCOVERY_PREFIX);
        config.allowControl = json.optBoolean("allowControl", DEFAULT_ALLOW_CONTROL);
        config.heartbeatSendAll = json.optBoolean("heartbeatSendAll", DEFAULT_HEARTBEAT_SEND_ALL);
        config.flushOnStateChange = json.optBoolean("flushOnStateChange", DEFAULT_FLUSH_ON_STATE_CHANGE);
        config.parkedIntervalSeconds = json.optInt("parkedIntervalSeconds", DEFAULT_PARKED_INTERVAL);
        config.chargingIntervalSeconds = json.optInt("chargingIntervalSeconds", DEFAULT_CHARGING_INTERVAL);
        if (config.minIntervalSeconds < 1) config.minIntervalSeconds = 1;
        if (config.maxIntervalSeconds < config.minIntervalSeconds) {
            config.maxIntervalSeconds = config.minIntervalSeconds;
        }
        // Per-state overrides: 0 = unset (use max). Negatives are meaningless, clamp to unset.
        if (config.parkedIntervalSeconds < 0) config.parkedIntervalSeconds = 0;
        if (config.chargingIntervalSeconds < 0) config.chargingIntervalSeconds = 0;
        return config;
    }

    @Override
    public String toString() {
        return "MqttConnectionConfig{" +
                "id='" + id + '\'' +
                ", name='" + name + '\'' +
                ", broker='" + brokerUrl + ":" + port + '\'' +
                ", topic='" + topic + '\'' +
                ", enabled=" + enabled +
                ", interval=[" + minIntervalSeconds + "-" + maxIntervalSeconds + "]s" +
                ", parkedInterval=" + parkedIntervalSeconds + "s" +
                ", chargingInterval=" + chargingIntervalSeconds + "s" +
                ", changeOnly=" + changeOnly +
                ", ha=" + homeAssistantDiscovery +
                ", ssl=" + isSsl() +
                ", trustAllCerts=" + trustAllCerts +
                '}';
    }
}

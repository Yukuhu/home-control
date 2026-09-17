package dev.andre.homecontrol.adapters.cast.protocol;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.Map;

/** Builders for every JSON payload the sender emits. Field names are the wire format. */
public final class CastPayloads {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private CastPayloads() {
    }

    /** Mirrors pychromecast's CONNECT; some Google TV builds refuse a bare {"type":"CONNECT"}. */
    public static ObjectNode connect() {
        ObjectNode node = type("CONNECT");
        node.putObject("origin");
        node.put("userAgent", "home-control");
        ObjectNode senderInfo = node.putObject("senderInfo");
        senderInfo.put("sdkType", 2);
        senderInfo.put("version", "15.605.1.3");
        senderInfo.put("browserVersion", "44.0.2403.30");
        senderInfo.put("platform", 4);
        senderInfo.put("systemVersion", "Macintosh; Intel Mac OS X10_10_3");
        senderInfo.put("connectionType", 1);
        return node;
    }

    public static ObjectNode close() {
        return type("CLOSE");
    }

    public static ObjectNode ping() {
        return type("PING");
    }

    public static ObjectNode pong() {
        return type("PONG");
    }

    /** Receiver and media namespaces share the same GET_STATUS shape. */
    public static ObjectNode getStatus() {
        return type("GET_STATUS");
    }

    public static ObjectNode launch(String appId) {
        ObjectNode node = type("LAUNCH");
        node.put("appId", appId);
        return node;
    }

    public static ObjectNode stop(String sessionId) {
        ObjectNode node = type("STOP");
        node.put("sessionId", sessionId);
        return node;
    }

    public static ObjectNode setVolumeLevel(double level) {
        ObjectNode node = type("SET_VOLUME");
        node.putObject("volume").put("level", Math.max(0.0, Math.min(1.0, level)));
        return node;
    }

    public static ObjectNode setMuted(boolean muted) {
        ObjectNode node = type("SET_VOLUME");
        node.putObject("volume").put("muted", muted);
        return node;
    }

    /** {@code body} is a media LOAD without type/requestId/sessionId (see {@code Action.CastLoad}). */
    public static ObjectNode load(String sessionId, Map<String, Object> body) {
        ObjectNode node = type("LOAD");
        node.setAll((ObjectNode) MAPPER.valueToTree(body));
        node.put("type", "LOAD");
        node.put("sessionId", sessionId);
        node.remove("requestId");
        return node;
    }

    /** A custom-namespace body, sent as given — no type, requestId or sessionId is added. */
    public static ObjectNode custom(Map<String, Object> message) {
        return (ObjectNode) MAPPER.valueToTree(message);
    }

    public static ObjectNode pause(long mediaSessionId) {
        return mediaCommand("PAUSE", mediaSessionId);
    }

    public static ObjectNode play(long mediaSessionId) {
        return mediaCommand("PLAY", mediaSessionId);
    }

    public static ObjectNode stopMedia(long mediaSessionId) {
        return mediaCommand("STOP", mediaSessionId);
    }

    /** A received payload as plain maps, lists and scalars, for callers outside the protocol package. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> toMap(JsonNode payload) {
        return payload != null && payload.isObject() ? MAPPER.convertValue(payload, Map.class) : Map.of();
    }

    public static String toJson(JsonNode node) {
        return MAPPER.writeValueAsString(node);
    }

    public static JsonNode parse(String json) {
        return MAPPER.readTree(json);
    }

    private static ObjectNode mediaCommand(String type, long mediaSessionId) {
        ObjectNode node = type(type);
        node.put("mediaSessionId", mediaSessionId);
        return node;
    }

    private static ObjectNode type(String type) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", type);
        return node;
    }
}

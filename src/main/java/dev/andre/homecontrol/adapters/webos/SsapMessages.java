package dev.andre.homecontrol.adapters.webos;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * SSAP wire format. Every frame is a JSON object {@code {id, type, uri?, payload}}; the TV echoes
 * the id. The registration manifest is aiowebostv's (unsigned; see Decisions).
 */
final class SsapMessages {

    static final JsonMapper JSON = JsonMapper.builder().build();

    static final List<String> PERMISSIONS = List.of(
            "APP_TO_APP", "CLOSE", "CONTROL_AUDIO", "CONTROL_DISPLAY", "CONTROL_INPUT_JOYSTICK",
            "CONTROL_INPUT_MEDIA_PLAYBACK", "CONTROL_INPUT_MEDIA_RECORDING", "CONTROL_INPUT_TEXT",
            "CONTROL_INPUT_TV", "CONTROL_MOUSE_AND_KEYBOARD", "CONTROL_POWER", "CONTROL_TV_SCREEN",
            "LAUNCH", "LAUNCH_WEBAPP", "READ_APP_STATUS", "READ_COUNTRY_INFO", "READ_CURRENT_CHANNEL",
            "READ_INPUT_DEVICE_LIST", "READ_INSTALLED_APPS", "READ_LGE_SDX", "READ_LGE_TV_INPUT_EVENTS",
            "READ_NETWORK_STATE", "READ_NOTIFICATIONS", "READ_POWER_STATE", "READ_RUNNING_APPS",
            "READ_SETTINGS", "READ_TV_CHANNEL_LIST", "READ_TV_CURRENT_TIME", "READ_UPDATE_INFO", "SEARCH",
            "TEST_OPEN", "TEST_PROTECTED", "TEST_SECURE", "UPDATE_FROM_REMOTE_APP",
            "WRITE_NOTIFICATION_ALERT", "WRITE_NOTIFICATION_TOAST", "WRITE_SETTINGS");

    private SsapMessages() {
    }

    /**
     * {@code {"type":"register","id":"register_0","payload":{"forcePairing":false,"pairingType":"PROMPT",
     * "client-key":"…","manifest":{"manifestVersion":1,"appVersion":"1.1","permissions":[…]}}}};
     * {@code client-key} only when one is stored.
     */
    static String register(String clientKey) {
        ObjectNode message = JSON.createObjectNode();
        message.put("type", "register");
        message.put("id", SsapConnection.REGISTER_ID);
        ObjectNode payload = message.putObject("payload");
        payload.put("forcePairing", false);
        payload.put("pairingType", "PROMPT");
        if (clientKey != null) {
            payload.put("client-key", clientKey);
        }
        ObjectNode manifest = payload.putObject("manifest");
        manifest.put("manifestVersion", 1);
        manifest.put("appVersion", "1.1");
        ArrayNode permissions = manifest.putArray("permissions");
        PERMISSIONS.forEach(permissions::add);
        return JSON.writeValueAsString(message);
    }

    /** {@code {"id":"req_7","type":"request","uri":"ssap://audio/setVolume","payload":{"volume":20}}}. */
    static String command(String id, String type, String uri, ObjectNode payload) {
        ObjectNode message = JSON.createObjectNode();
        message.put("id", id);
        message.put("type", type);
        message.put("uri", uri);
        message.set("payload", payload == null ? JSON.createObjectNode() : payload);
        return JSON.writeValueAsString(message);
    }

    /** The pointer input socket speaks lines, not JSON: {@code type:button\nname:UP\n\n}. */
    static String button(String name) {
        return "type:button\nname:" + name + "\n\n";
    }

    static ObjectNode empty() {
        return JSON.createObjectNode();
    }
}

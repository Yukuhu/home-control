package dev.andre.homecontrol.adapters.tizen;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * The Samsung remote-control WebSocket wire format (samsung-tv-ws-api {@code connection.py}, {@code remote.py}).
 * The URI carries the token: log it only through {@code TextWebSocket.withoutQuery}.
 */
final class TizenMessages {

    static final JsonMapper JSON = JsonMapper.builder().build();

    private TizenMessages() {
    }

    /** The TV shows {@code name} in its Allow prompt and device list; the token proves an earlier Allow. */
    static URI remoteUri(String host, int port, String clientName, String token) {
        String authority = host.contains(":") ? "[" + host + "]" : host;
        String name = Base64.getEncoder().encodeToString(clientName.getBytes(StandardCharsets.UTF_8));
        StringBuilder uri = new StringBuilder("wss://").append(authority).append(':').append(port)
                .append("/api/v2/channels/samsung.remote.control?name=")
                .append(URLEncoder.encode(name, StandardCharsets.UTF_8));
        if (token != null && !token.isBlank()) {
            uri.append("&token=").append(URLEncoder.encode(token, StandardCharsets.UTF_8));
        }
        return URI.create(uri.toString());
    }

    /** A short press. */
    static String key(String code) {
        return key(code, "Click");
    }

    /** {@code command}: {@code Click}, or {@code Press} / {@code Release} around a held key. */
    static String key(String code, String command) {
        ObjectNode message = JSON.createObjectNode();
        message.put("method", "ms.remote.control");
        ObjectNode params = message.putObject("params");
        params.put("Cmd", command);
        params.put("DataOfCmd", code);
        params.put("Option", "false");
        params.put("TypeOfRemote", "SendRemoteKey");
        return JSON.writeValueAsString(message);
    }

    static String launchApp(String appId, String actionType) {
        ObjectNode message = JSON.createObjectNode();
        message.put("method", "ms.channel.emit");
        ObjectNode params = message.putObject("params");
        params.put("event", "ed.apps.launch");
        params.put("to", "host");
        ObjectNode data = params.putObject("data");
        data.put("action_type", actionType);
        data.put("appId", appId);
        data.put("metaTag", "");
        return JSON.writeValueAsString(message);
    }

    static String installedAppsRequest() {
        ObjectNode message = JSON.createObjectNode();
        message.put("method", "ms.channel.emit");
        ObjectNode params = message.putObject("params");
        params.put("event", "ed.installedApp.get");
        params.put("to", "host");
        return JSON.writeValueAsString(message);
    }

    static List<TizenApp> installedApps(JsonNode event) {
        List<TizenApp> apps = new ArrayList<>();
        for (JsonNode app : event.path("data").path("data").values()) {
            String appId = app.path("appId").asString("");
            if (!appId.isEmpty()) {
                apps.add(new TizenApp(appId, app.path("name").asString(""), app.path("app_type").asInt(2)));
            }
        }
        return List.copyOf(apps);
    }
}

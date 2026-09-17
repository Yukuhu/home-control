package dev.andre.homecontrol.sources.jellyfin;

import dev.andre.homecontrol.core.playback.PlayableRef;
import tools.jackson.databind.JsonNode;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The PlayNow request the Jellyfin Cast receiver (jellyfin-chromecast) accepts on its custom
 * namespace — the same shape jellyfin-web's chromecastPlayer plugin sends.
 */
public final class JellyfinCastMessages {

    public static final String NAMESPACE = "urn:x-cast:com.connectsdk";
    public static final String RECEIVER_LABEL = "the Jellyfin receiver";

    private JellyfinCastMessages() {
    }

    public static Map<String, Object> playNow(JellyfinSettings settings, String accessToken, JsonNode item,
                                              long startPositionTicks, String receiverName) {
        Map<String, Object> stub = new LinkedHashMap<>();
        stub.put("Id", item.path("Id").asString(""));
        stub.put("ServerId", item.path("ServerId").asString(settings.serverId()));
        stub.put("Name", item.path("Name").asString(""));
        stub.put("Type", item.path("Type").asString(""));
        stub.put("MediaType", item.path("MediaType").asString(""));
        stub.put("IsFolder", item.path("IsFolder").asBoolean(false));
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("items", List.of(Collections.unmodifiableMap(stub)));
        if (startPositionTicks > 0) {
            options.put("startPositionTicks", startPositionTicks);
        }
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("options", Collections.unmodifiableMap(options));
        message.put("command", "PlayNow");
        message.put("userId", settings.userId());
        message.put("deviceId", settings.deviceId());
        message.put("accessToken", accessToken);
        message.put("serverAddress", settings.deviceServerUrl().toString());
        message.put("serverId", settings.serverId());
        message.put("serverVersion", settings.serverVersion());
        message.put("receiverName", receiverName);
        return Collections.unmodifiableMap(message);
    }

    public static PlayableRef.CastMessage playable(JellyfinSettings settings, String accessToken, JsonNode item,
                                                   long startPositionTicks, String receiverName) {
        return new PlayableRef.CastMessage(settings.castReceiverId(), NAMESPACE,
                playNow(settings, accessToken, item, startPositionTicks, receiverName), RECEIVER_LABEL);
    }
}

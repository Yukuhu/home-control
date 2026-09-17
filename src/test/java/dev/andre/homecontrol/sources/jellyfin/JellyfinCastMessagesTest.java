package dev.andre.homecontrol.sources.jellyfin;

import dev.andre.homecontrol.core.playback.PlayableRef;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JellyfinCastMessagesTest {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final JellyfinSettings settings = new JellyfinSettings(URI.create("http://jellyfin:8096"),
            URI.create("http://192.168.1.20:8096"), "4e1a2b3c4d5e4f60718293a4b5c6d7e8", "nas", "10.11.2",
            "a1b2c3d4e5f60718293a4b5c6d7e8f90", "andre", JellyfinSettings.AuthMode.PASSWORD,
            "0123456789abcdef0123456789abcdef", "F007D354", Map.of());

    @Test
    void buildsTheMessageJellyfinWebSends() {
        JsonNode item = mapper.readTree(FakeJellyfinServer.fixture("item-episode.json"));

        Map<String, Object> message = JellyfinCastMessages.playNow(settings, "6c1f0e5a9b8d4c7e8f2a3b4c5d6e7f80", item, 6_120_000_000L, "Living Room TV");

        assertThat(mapper.readTree(mapper.writeValueAsString(message))).isEqualTo(mapper.readTree("""
                {"options":{"items":[{"Id":"3f2a9c1e7b6d4e5f8a9b0c1d2e3f4a5b","ServerId":"4e1a2b3c4d5e4f60718293a4b5c6d7e8",
                  "Name":"The Long Night","Type":"Episode","MediaType":"Video","IsFolder":false}],
                  "startPositionTicks":6120000000},
                 "command":"PlayNow","userId":"a1b2c3d4e5f60718293a4b5c6d7e8f90","deviceId":"0123456789abcdef0123456789abcdef",
                 "accessToken":"6c1f0e5a9b8d4c7e8f2a3b4c5d6e7f80","serverAddress":"http://192.168.1.20:8096",
                 "serverId":"4e1a2b3c4d5e4f60718293a4b5c6d7e8","serverVersion":"10.11.2","receiverName":"Living Room TV"}
                """));
    }

    @Test
    void startsFromTheBeginningWithoutAStartPosition() {
        JsonNode item = mapper.readTree(FakeJellyfinServer.fixture("item-movie.json"));

        @SuppressWarnings("unchecked")
        Map<String, Object> options = (Map<String, Object>) JellyfinCastMessages.playNow(settings, "t", item, 0, "Kitchen").get("options");
        assertThat(options).doesNotContainKey("startPositionTicks");
    }

    @Test
    void thePlayableUsesTheUsersReceiverApp() {
        JsonNode item = mapper.readTree(FakeJellyfinServer.fixture("item-movie.json"));
        JellyfinSettings unstable = new JellyfinSettings(settings.serverUrl(), settings.deviceServerUrl(), settings.serverId(),
                settings.serverName(), settings.serverVersion(), settings.userId(), settings.userName(), settings.authMode(),
                settings.deviceId(), "6F511C87", Map.of());

        PlayableRef.CastMessage playable = JellyfinCastMessages.playable(unstable, "t", item, 0, "Kitchen");

        assertThat(playable.receiverAppId()).isEqualTo("6F511C87");
        assertThat(playable.namespace()).isEqualTo("urn:x-cast:com.connectsdk");
        assertThat(playable.receiverLabel()).isEqualTo("the Jellyfin receiver");
    }
}

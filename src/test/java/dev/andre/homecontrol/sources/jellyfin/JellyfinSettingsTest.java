package dev.andre.homecontrol.sources.jellyfin;

import dev.andre.homecontrol.storage.JsonFileSourceSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JellyfinSettingsTest {

    private final JellyfinSettings settings = new JellyfinSettings(
            URI.create("http://192.168.1.20:8096"), URI.create("http://192.168.1.20:8096"),
            "server-1", "nas", "10.11.2", "user-1", "andre", JellyfinSettings.AuthMode.PASSWORD,
            "device-1", "F007D354", Map.of());

    @TempDir
    Path dir;

    private JellyfinSettings roundTrip(JellyfinSettings stored) {
        Path file = dir.resolve("sources.json");
        new JsonFileSourceSettings(file).put(JellyfinSettings.SOURCE_ID, stored);
        return new JsonFileSourceSettings(file)
                .get(JellyfinSettings.SOURCE_ID, JellyfinSettings.class, JellyfinSettings::fromVersionOne).orElseThrow();
    }

    @Test
    void playerChoiceIsPerDeviceAndCanBeResetWithoutLosingLinks() {
        var vlc = settings.withPlayer("shield", JellyfinSettings.Player.VLC).withSessionLink("shield", "jf-shield");
        assertThat(vlc.player("bedroom")).isEqualTo(JellyfinSettings.Player.JELLYFIN);
        assertThat(vlc.player("shield")).isEqualTo(JellyfinSettings.Player.VLC);
        var reset = vlc.withPlayer("shield", JellyfinSettings.Player.JELLYFIN);
        assertThat(roundTrip(reset).player("shield")).isEqualTo(JellyfinSettings.Player.JELLYFIN);
        assertThat(reset.sessionLinks()).containsEntry("shield", "jf-shield");
    }

    @Test
    void versionOneKeepsPerDevicePlayersAlongsideSessionLinks() {
        Map<String, String> flat = new LinkedHashMap<>();
        flat.put("serverUrl", "http://192.168.1.20:8096");
        flat.put("userId", "user-1");
        flat.put("link.shield", "jf-shield");
        flat.put("player.shield", "vlc");

        JellyfinSettings converted = JellyfinSettings.fromVersionOne(flat).orElseThrow();

        assertThat(converted.player("shield")).isEqualTo(JellyfinSettings.Player.VLC);
        assertThat(converted.sessionLinks()).containsExactly(Map.entry("shield", "jf-shield"));
        assertThat(converted.deviceServerUrl()).isEqualTo(URI.create("http://192.168.1.20:8096"));
        assertThat(converted.castReceiverId()).isEqualTo(JellyfinSettings.DEFAULT_CAST_RECEIVER_ID);
    }

    @Test
    void roundTripsThroughSourcesJson() {
        JellyfinSettings withLinks = settings.withSessionLink("living-room", "jf-living")
                .withSessionLink("bedroom", "jf-bedroom").withPlayer("bedroom", JellyfinSettings.Player.VLC);

        assertThat(roundTrip(withLinks)).isEqualTo(withLinks);
    }

    @Test
    void aVersionOneSectionWithoutServerUrlIsNotConfigured() {
        assertThat(JellyfinSettings.fromVersionOne(Map.of())).isEmpty();
    }

    @Test
    void theVersionOneFixtureConvertsLinksAndPlayersAndRoundTripsThroughTheStore() throws IOException {
        Path file = dir.resolve("sources.json");
        Files.copy(Path.of("src/test/resources/fixtures/sources/sources-v1.json"), file);

        JellyfinSettings converted = new JsonFileSourceSettings(file)
                .get(JellyfinSettings.SOURCE_ID, JellyfinSettings.class, JellyfinSettings::fromVersionOne).orElseThrow();

        assertThat(converted.sessionLinks()).containsExactly(Map.entry("shield-1", "jf-dev-9"));
        assertThat(converted.player("shield-1")).isEqualTo(JellyfinSettings.Player.VLC);
        assertThat(new JsonFileSourceSettings(file)
                .get(JellyfinSettings.SOURCE_ID, JellyfinSettings.class, JellyfinSettings::fromVersionOne))
                .contains(converted);
        JsonNode section = JsonMapper.builder().build().readTree(Files.readAllBytes(file)).path("sources").path("jellyfin");
        assertThat(section.path("players").path("shield-1").asString()).isEqualTo("VLC");
        assertThat(section.path("sessionLinks").path("shield-1").asString()).isEqualTo("jf-dev-9");
        assertThat(section.path("serverUrl").asString()).isEqualTo("http://nas:8096");
    }

    @Test
    void flagsDeviceAddressesTvsCannotReach() {
        assertThat(local("http://localhost:8096")).isTrue();
        assertThat(local("http://127.0.0.1:8096")).isTrue();
        assertThat(local("http://[::1]:8096")).isTrue();
        assertThat(local("http://jellyfin:8096")).isTrue();

        assertThat(local("http://192.168.1.20:8096")).isFalse();
        assertThat(local("http://nas.lan:8096")).isFalse();
        assertThat(local("https://media.example.org")).isFalse();
    }

    private boolean local(String deviceServerUrl) {
        return new JellyfinSettings(settings.serverUrl(), URI.create(deviceServerUrl), settings.serverId(),
                settings.serverName(), settings.serverVersion(), settings.userId(), settings.userName(),
                settings.authMode(), settings.deviceId(), settings.castReceiverId(), settings.sessionLinks())
                .deviceAddressLooksLocal();
    }
}

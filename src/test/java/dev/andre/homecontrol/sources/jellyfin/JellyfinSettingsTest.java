package dev.andre.homecontrol.sources.jellyfin;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class JellyfinSettingsTest {

    private final JellyfinSettings settings = new JellyfinSettings(
            URI.create("http://192.168.1.20:8096"), URI.create("http://192.168.1.20:8096"),
            "server-1", "nas", "10.11.2", "user-1", "andre", JellyfinSettings.AuthMode.PASSWORD,
            "device-1", "F007D354", Map.of());

    @Test
    void playerChoiceIsPerDeviceAndCanBeResetWithoutLosingLinks() {
        var vlc = settings.withPlayer("shield", JellyfinSettings.Player.VLC).withSessionLink("shield", "jf-shield");
        assertThat(vlc.player("bedroom")).isEqualTo(JellyfinSettings.Player.JELLYFIN);
        assertThat(vlc.player("shield")).isEqualTo(JellyfinSettings.Player.VLC);
        var reset = vlc.withPlayer("shield", JellyfinSettings.Player.JELLYFIN);
        assertThat(JellyfinSettings.from(reset.toMap()).orElseThrow().player("shield")).isEqualTo(JellyfinSettings.Player.JELLYFIN);
        assertThat(reset.sessionLinks()).containsEntry("shield", "jf-shield");
    }

    @Test
    void retainsPerDevicePlayerPreferencesAlongsideSessionLinks() {
        var stored = new java.util.LinkedHashMap<>(settings.withSessionLink("shield", "jf-shield").toMap());
        stored.put("player.shield", "vlc");
        assertThat(JellyfinSettings.from(stored).orElseThrow().toMap())
                .containsEntry("player.shield", "vlc").containsEntry("link.shield", "jf-shield");
    }

    @Test
    void roundTripsThroughAStringMap() {
        JellyfinSettings withLinks = settings.withSessionLink("living-room", "jf-living")
                .withSessionLink("bedroom", "jf-bedroom");

        Optional<JellyfinSettings> restored = JellyfinSettings.from(withLinks.toMap());

        assertThat(restored).contains(withLinks);
        assertThat(withLinks.toMap()).containsEntry("link.living-room", "jf-living")
                .containsEntry("link.bedroom", "jf-bedroom");
    }

    @Test
    void aMapWithoutServerUrlIsNotConfigured() {
        assertThat(JellyfinSettings.from(Map.of())).isEmpty();
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

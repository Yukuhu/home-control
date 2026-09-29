package dev.andre.homecontrol.sources.youtube;

import dev.andre.homecontrol.storage.JsonFileSourceSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class YouTubeSettingsTest {

    @TempDir
    Path dir;

    private YouTubeSettings everyField() {
        Map<String, String> playlists = new LinkedHashMap<>();
        playlists.put("PLa", "Music");
        playlists.put("PLb", "Watch later picks");
        return new YouTubeSettings(Instant.parse("2026-09-16T10:00:00Z"), "chan-1", "Andre",
                true, playlists, Set.of("b", "a"), "remote-1");
    }

    @Test
    void roundTripsEveryFieldThroughSourcesJson() {
        Path file = dir.resolve("sources.json");
        new JsonFileSourceSettings(file).put(YouTubeSettings.SOURCE_ID, everyField());

        assertThat(YouTubeSettings.read(new JsonFileSourceSettings(file))).isEqualTo(everyField());
    }

    @Test
    void versionOneReadsEveryKey() {
        Map<String, String> flat = Map.of(
                "connectedAt", "2026-09-16T10:00:00Z",
                "channelId", "chan-1",
                "channelTitle", "Andre",
                "watchLater", "true",
                "playlist.PLa", "Music",
                "playlist.PLb", "Watch later picks",
                "lounge.devices", "a,b",
                "lounge.remoteId", "remote-1");

        assertThat(YouTubeSettings.fromVersionOne(flat)).isEqualTo(everyField());
    }

    @Test
    void nothingStoredIsNotConnected() {
        assertThat(YouTubeSettings.read(new JsonFileSourceSettings(dir.resolve("sources.json"))))
                .isEqualTo(YouTubeSettings.EMPTY);
    }

    @Test
    void theVersionOneFixtureConvertsPlaylistsAndLoungeDevicesAndRoundTripsThroughTheStore() throws IOException {
        Path file = dir.resolve("sources.json");
        Files.copy(Path.of("src/test/resources/fixtures/sources/sources-v1.json"), file);

        YouTubeSettings converted = YouTubeSettings.read(new JsonFileSourceSettings(file));

        assertThat(converted.playlists()).containsExactly(Map.entry("PL1", "Cooking"), Map.entry("PL2", "Music"));
        assertThat(converted.loungeDevices()).containsExactly("shield-1", "tv-2");
        assertThat(converted.watchLater()).isTrue();
        assertThat(YouTubeSettings.read(new JsonFileSourceSettings(file))).isEqualTo(converted);
        JsonNode section = JsonMapper.builder().build().readTree(Files.readAllBytes(file)).path("sources").path("youtube");
        assertThat(section.path("loungeDevices").isArray()).isTrue();
        assertThat(section.path("playlists").path("PL2").asString()).isEqualTo("Music");
    }

    @Test
    void anEmptyVersionOneSectionIsNotConnected() {
        YouTubeSettings settings = YouTubeSettings.fromVersionOne(Map.of());

        assertThat(settings.connectedAt()).isNull();
        assertThat(settings.watchLater()).isFalse();
        assertThat(settings.playlists()).isEmpty();
        assertThat(settings.loungeDevices()).isEmpty();
        assertThat(settings.loungeRemoteId()).isNull();
    }

    @Test
    void withoutAccountKeepsLounge() {
        YouTubeSettings settings = new YouTubeSettings(Instant.parse("2026-09-16T10:00:00Z"), "chan-1", "Andre",
                true, Map.of("PLa", "Music"), Set.of("living-room"), "remote-1");

        YouTubeSettings cleared = settings.withoutAccount();

        assertThat(cleared.connectedAt()).isNull();
        assertThat(cleared.channelId()).isNull();
        assertThat(cleared.channelTitle()).isNull();
        assertThat(cleared.watchLater()).isFalse();
        assertThat(cleared.playlists()).isEmpty();
        assertThat(cleared.loungeDevices()).isEqualTo(Set.of("living-room"));
        assertThat(cleared.loungeRemoteId()).isEqualTo("remote-1");
    }

    @Test
    void playlistsAreOrderedByTitle() {
        Map<String, String> map = Map.of("playlist.PLb", "Zebra", "playlist.PLa", "apple");

        YouTubeSettings settings = YouTubeSettings.fromVersionOne(map);

        assertThat(settings.playlists().keySet()).containsExactly("PLa", "PLb");
    }

    @Test
    void loungeDevicesIterateInSortedOrder() {
        YouTubeSettings settings = new YouTubeSettings(null, null, null, false, Map.of(),
                Set.of("kitchen", "attic", "living-room"), null);

        assertThat(settings.loungeDevices()).containsExactly("attic", "kitchen", "living-room");

        YouTubeSettings withAdded = settings.withLoungeDevice("basement", true);

        assertThat(withAdded.loungeDevices()).containsExactly("attic", "basement", "kitchen", "living-room");
    }
}

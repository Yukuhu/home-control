package dev.andre.homecontrol.sources.youtube;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class YouTubeSettingsTest {

    @Test
    void roundTripsEveryKey() {
        Map<String, String> playlists = new LinkedHashMap<>();
        playlists.put("PLa", "Music");
        playlists.put("PLb", "Watch later picks");
        YouTubeSettings settings = new YouTubeSettings(Instant.parse("2026-09-16T10:00:00Z"), "chan-1", "Andre",
                true, playlists, Set.of("b", "a"), "remote-1");

        Map<String, String> map = settings.toMap();

        assertThat(map).containsExactlyInAnyOrderEntriesOf(Map.of(
                "connectedAt", "2026-09-16T10:00:00Z",
                "channelId", "chan-1",
                "channelTitle", "Andre",
                "watchLater", "true",
                "playlist.PLa", "Music",
                "playlist.PLb", "Watch later picks",
                "lounge.devices", "a,b",
                "lounge.remoteId", "remote-1"));
        assertThat(YouTubeSettings.from(map)).isEqualTo(settings);
    }

    @Test
    void emptyMapIsNotConnected() {
        YouTubeSettings settings = YouTubeSettings.from(Map.of());

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

        YouTubeSettings settings = YouTubeSettings.from(map);

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

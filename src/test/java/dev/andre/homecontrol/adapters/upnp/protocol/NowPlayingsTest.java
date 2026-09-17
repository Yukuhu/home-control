package dev.andre.homecontrol.adapters.upnp.protocol;

import dev.andre.homecontrol.core.NowPlaying;
import dev.andre.homecontrol.core.PlaybackState;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class NowPlayingsTest {

    private static Map<String, String> answer() throws IOException {
        Map<String, String> answer = new HashMap<>();
        answer.put("TrackURI", "http://nas/a.flac");
        answer.put("TrackMetaData", Files.readString(Path.of("src/test/resources/fixtures/upnp/didl-track.xml")));
        answer.put("RelTime", "0:00:42");
        answer.put("TrackDuration", "0:03:07");
        return answer;
    }

    private static TransportInfo transport(String state) {
        return new TransportInfo(state, "OK");
    }

    @Test
    void mapsTransportStates() throws IOException {
        PositionInfo position = PositionInfo.from(answer());

        assertThat(NowPlayings.of(transport("PLAYING"), position, null))
                .isEqualTo(new NowPlaying("Bunny Song", PlaybackState.PLAYING, 42.0, 187.0));
        assertThat(NowPlayings.of(transport("PAUSED_PLAYBACK"), position, null).state()).isEqualTo(PlaybackState.PAUSED);
        assertThat(NowPlayings.of(transport("TRANSITIONING"), position, null).state()).isEqualTo(PlaybackState.BUFFERING);
        assertThat(NowPlayings.of(transport("STOPPED"), position, null)).isNull();
        assertThat(NowPlayings.of(transport("NO_MEDIA_PRESENT"), position, null)).isNull();
    }

    @Test
    void fallsBackToTheTitleThisServerSent() throws IOException {
        Map<String, String> answer = answer();
        answer.put("TrackMetaData", "NOT_IMPLEMENTED");
        PositionInfo position = PositionInfo.from(answer);

        assertThat(NowPlayings.of(transport("PLAYING"), position, new PlayedItem("http://nas/a.flac", "Bunny Song")).title())
                .isEqualTo("Bunny Song");
        assertThat(NowPlayings.of(transport("PLAYING"), position, new PlayedItem("http://nas/other.flac", "Other")).title())
                .isEqualTo("Unknown title");
        assertThat(NowPlayings.of(transport("PLAYING"), position, null).title()).isEqualTo("Unknown title");
    }

    @Test
    void aZeroDurationIsUnknown() throws IOException {
        Map<String, String> answer = answer();
        answer.put("TrackDuration", "0:00:00");
        answer.put("RelTime", "NOT_IMPLEMENTED");

        NowPlaying playing = NowPlayings.of(transport("PLAYING"), PositionInfo.from(answer), null);

        assertThat(playing.durationSeconds()).isNull();
        assertThat(playing.positionSeconds()).isZero();
    }

    @Test
    void positionsNeverPrintTheTrackUri() throws IOException {
        Map<String, String> answer = answer();
        answer.put("TrackURI", "http://nas/a.flac?ApiKey=secret-key");

        assertThat(PositionInfo.from(answer).toString()).doesNotContain("secret-key");
        assertThat(new PlayedItem("http://nas/a.flac?ApiKey=secret-key", "T").toString()).doesNotContain("secret-key");
    }
}

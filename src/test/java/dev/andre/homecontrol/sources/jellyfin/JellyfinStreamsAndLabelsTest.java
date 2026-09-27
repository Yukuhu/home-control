package dev.andre.homecontrol.sources.jellyfin;

import dev.andre.homecontrol.core.playback.PlayableRef;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

/** Which media source becomes the direct stream, and how an episode is labelled. */
class JellyfinStreamsAndLabelsTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Test
    void theFirstDirectlyPlayableSourceInAKnownContainerWins() {
        var item = MAPPER.readTree("""
                {"Id":"c0ffee00c0ffee00c0ffee00c0ffee01","MediaType":"Video"}
                """);
        var info = MAPPER.readTree("""
                {"MediaSources":[
                  {"Id":"","SupportsDirectPlay":true,"Container":"mp4"},
                  {"Id":"a","SupportsDirectPlay":false,"Container":"mp4"},
                  {"Id":"b","Container":"mp4"},
                  {"Id":"c","SupportsDirectPlay":true,"Container":"avi"},
                  {"Id":"d","SupportsDirectPlay":true,"Container":"MKV, WebM"},
                  {"Id":"e","SupportsDirectPlay":true,"Container":"mp4"}
                ]}
                """);

        assertThat(JellyfinStreams.fromPlaybackInfo(URI.create("http://10.0.0.2:8096"), "t", item, info))
                .contains(new PlayableRef.StreamUrl(URI.create("http://10.0.0.2:8096/Videos/c0ffee00c0ffee00c0ffee00c0ffee01"
                        + "/stream.webm?static=true&mediaSourceId=d&ApiKey=t"), "video/webm"));
    }

    @Test
    void anEpisodeLabelUsesWhateverNumbersAndNameThereAre() {
        assertThat(JellyfinItemMapper.episodeLabel(MAPPER.readTree("{}"), "Pilot")).isEqualTo("Pilot");
        assertThat(JellyfinItemMapper.episodeLabel(MAPPER.readTree("{}"), " ")).isNull();
        assertThat(JellyfinItemMapper.episodeLabel(MAPPER.readTree("{\"IndexNumber\":3}"), "Pilot"))
                .isEqualTo("E3 · Pilot");
        assertThat(JellyfinItemMapper.episodeLabel(MAPPER.readTree("{\"ParentIndexNumber\":1,\"IndexNumber\":2}"), ""))
                .isEqualTo("S1:E2");
        assertThat(JellyfinItemMapper.episodeLabel(MAPPER.readTree("{\"ParentIndexNumber\":1,\"IndexNumber\":2}"), "Two"))
                .isEqualTo("S1:E2 · Two");
    }
}

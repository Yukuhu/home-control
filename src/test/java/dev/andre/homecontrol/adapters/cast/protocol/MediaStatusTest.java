package dev.andre.homecontrol.adapters.cast.protocol;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MediaStatusTest {

    private static List<MediaStatus> fixture(String name) throws Exception {
        return MediaStatus.parse(CastPayloads.parse(
                Files.readString(Path.of("src/test/resources/fixtures/cast/" + name))).path("status"));
    }

    @Test
    void readsAPlayingStatusWithItsMedia() throws Exception {
        MediaStatus status = fixture("media-status-playing.json").getFirst();

        assertThat(status.mediaSessionId()).isEqualTo(1);
        assertThat(status.state()).isEqualTo(MediaStatus.PlayerState.PLAYING);
        assertThat(status.currentTime()).isEqualTo(12.5);
        assertThat(status.title()).isEqualTo("Big Buck Bunny");
        assertThat(status.duration()).isEqualTo(596.474195);
        assertThat(status.contentId()).isEqualTo("http://nas.local/films/bunny.mp4");
        assertThat(status.displayTitle()).isEqualTo("Big Buck Bunny");
    }

    @Test
    void aPartialStatusKeepsWhatThePreviousOneKnewForTheSameSession() throws Exception {
        MediaStatus playing = fixture("media-status-playing.json").getFirst();
        MediaStatus partial = fixture("media-status-partial.json").getFirst();

        assertThat(partial.title()).isNull();
        assertThat(partial.displayTitle()).isEqualTo("Unknown media");

        MediaStatus filled = partial.fillFrom(playing);

        assertThat(filled.title()).isEqualTo("Big Buck Bunny");
        assertThat(filled.duration()).isEqualTo(596.474195);
        assertThat(filled.state()).isEqualTo(MediaStatus.PlayerState.PAUSED);
        assertThat(filled.currentTime()).isEqualTo(30.2);
    }

    @Test
    void doesNotFillFromADifferentMediaSession() throws Exception {
        MediaStatus other = new MediaStatus(2, "PLAYING", 1.0, "http://x/other.mp4", "Other", 10.0, null);

        assertThat(fixture("media-status-partial.json").getFirst().fillFrom(other).title()).isNull();
        assertThat(fixture("media-status-partial.json").getFirst().fillFrom(null).title()).isNull();
    }

    @Test
    void readsIdleAndEmptyStatuses() throws Exception {
        MediaStatus idle = fixture("media-status-idle.json").getFirst();

        assertThat(idle.state()).isEqualTo(MediaStatus.PlayerState.IDLE);
        assertThat(idle.idleReason()).isEqualTo("FINISHED");
        assertThat(MediaStatus.parse(CastPayloads.parse("[]"))).isEmpty();
    }

    @Test
    void anUnknownPlayerStateCountsAsBufferingAndTheFileNameIsAFallbackTitle() {
        MediaStatus loading = new MediaStatus(1, "LOADING", 0, "http://nas.local/films/big%20buck.mp4?token=1", null, null, null);

        assertThat(loading.state()).isEqualTo(MediaStatus.PlayerState.BUFFERING);
        assertThat(loading.displayTitle()).isEqualTo("big buck.mp4");
        assertThat(new MediaStatus(1, "PLAYING", 0, "http://nas.local/c++.mp4", null, null, null).displayTitle())
                .isEqualTo("c++.mp4");
    }

    @Test
    void loadingAndUnknownStatesAreBuffering() {
        assertThat(status("LOADING").state()).isEqualTo(MediaStatus.PlayerState.BUFFERING);
        assertThat(status("SOMETHING_NEWER").state()).isEqualTo(MediaStatus.PlayerState.BUFFERING);
    }

    private static MediaStatus status(String playerState) {
        return new MediaStatus(1, playerState, 0.0, null, null, null, null);
    }
}

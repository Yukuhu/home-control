package dev.andre.homecontrol.adapters.androidtv;

import dev.andre.homecontrol.core.LaunchedMedia;
import dev.andre.homecontrol.core.NowPlaying;
import dev.andre.homecontrol.core.PlaybackState;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class InferredPlaybackTest {

    private static final String VLC = "org.videolan.vlc";
    private static final Instant LAUNCH = Instant.parse("2026-09-27T20:00:00Z");
    private static final LaunchedMedia MOVIE = new LaunchedMedia(VLC, "Big Buck Bunny", 600.0);

    private final InferredPlayback playback = new InferredPlayback();

    @Test
    void reportsNothingBeforeAnyLaunch() {
        assertThat(playback.current(LAUNCH)).isNull();
        assertThat(playback.nextDeadline()).isEmpty();
    }

    @Test
    void reportsTheLaunchedMediaWithoutAPositionOnceItsAppIsInFront() {
        playback.launched(MOVIE, "com.google.android.tvlauncher", LAUNCH);
        assertThat(playback.current(LAUNCH)).isNull();

        playback.appChanged(VLC);

        assertThat(playback.current(LAUNCH.plusSeconds(2)))
                .isEqualTo(new NowPlaying("Big Buck Bunny", PlaybackState.PLAYING, null, 600.0));
    }

    @Test
    void reportsAtOnceWhenTheAppWasAlreadyInFront() {
        playback.launched(MOVIE, VLC, LAUNCH);

        assertThat(playback.current(LAUNCH)).isNotNull();
    }

    @Test
    void forgetsTheMediaWhenAnotherAppComesToTheFront() {
        playback.launched(MOVIE, VLC, LAUNCH);

        playback.appChanged("com.netflix.ninja");
        playback.appChanged(VLC);

        assertThat(playback.current(LAUNCH.plusSeconds(5))).isNull();
    }

    @Test
    void anotherAppReportedBeforeTheLaunchedOneAppearsDoesNotForgetTheMedia() {
        playback.launched(MOVIE, null, LAUNCH);

        playback.appChanged("com.google.android.tvlauncher");
        playback.appChanged(VLC);

        assertThat(playback.current(LAUNCH.plusSeconds(5))).isNotNull();
    }

    @Test
    void forgetsTheMediaWhenItsAppNeverAppears() {
        playback.launched(MOVIE, "com.google.android.tvlauncher", LAUNCH);
        assertThat(playback.nextDeadline()).contains(LAUNCH.plus(InferredPlayback.APP_GRACE));

        assertThat(playback.current(LAUNCH.plus(InferredPlayback.APP_GRACE))).isNull();
        playback.appChanged(VLC);

        assertThat(playback.current(LAUNCH.plus(InferredPlayback.APP_GRACE).plusSeconds(1))).isNull();
    }

    @Test
    void forgetsTheMediaWhenTheDevicePowersOff() {
        playback.launched(MOVIE, VLC, LAUNCH);

        playback.poweredOff();

        assertThat(playback.current(LAUNCH.plusSeconds(5))).isNull();
    }

    @Test
    void expiresOnceTheRuntimeAndAMarginHavePassed() {
        playback.launched(MOVIE, VLC, LAUNCH);
        Instant expiry = LAUNCH.plusSeconds(600).plus(InferredPlayback.EXPIRY_MARGIN);
        assertThat(playback.nextDeadline()).contains(expiry);

        assertThat(playback.current(expiry.minusSeconds(1))).isNotNull();
        assertThat(playback.current(expiry)).isNull();
        assertThat(playback.nextDeadline()).isEmpty();
    }

    @Test
    void mediaOfUnknownLengthStaysUntilTheAppChanges() {
        playback.launched(new LaunchedMedia(VLC, "Live stream", null), VLC, LAUNCH);

        assertThat(playback.nextDeadline()).isEmpty();
        assertThat(playback.current(LAUNCH.plus(Duration.ofDays(1))))
                .isEqualTo(new NowPlaying("Live stream", PlaybackState.PLAYING, null, null));
    }

    @Test
    void aNewLaunchReplacesTheOneBefore() {
        playback.launched(MOVIE, VLC, LAUNCH);

        playback.launched(new LaunchedMedia(VLC, "Sintel", 888.0), VLC, LAUNCH.plusSeconds(60));

        assertThat(playback.current(LAUNCH.plusSeconds(61)).title()).isEqualTo("Sintel");
    }
}

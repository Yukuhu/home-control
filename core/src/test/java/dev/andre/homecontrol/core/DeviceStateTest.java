package dev.andre.homecontrol.core;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class DeviceStateTest {

    private static final NowPlaying BUNNY = new NowPlaying("Big Buck Bunny", PlaybackState.PLAYING, 12.5, 596.5);

    @Test
    void theSevenArgumentConstructorHasNothingPlaying() {
        assertThat(new DeviceState(DeviceStatus.CONNECTED, true, null, 1, 100, false, Instant.EPOCH).nowPlaying()).isNull();
        assertThat(DeviceState.initial().nowPlaying()).isNull();
    }

    @Test
    void everyWitherKeepsWhatIsPlaying() {
        DeviceState playing = DeviceState.initial().withNowPlaying(BUNNY);

        assertThat(playing.withStatus(DeviceStatus.CONNECTED).nowPlaying()).isEqualTo(BUNNY);
        assertThat(playing.withPower(true).nowPlaying()).isEqualTo(BUNNY);
        assertThat(playing.withCurrentApp("Default Media Receiver").nowPlaying()).isEqualTo(BUNNY);
        assertThat(playing.withVolume(3, 100, false).nowPlaying()).isEqualTo(BUNNY);
        assertThat(playing.withNowPlaying(null).nowPlaying()).isNull();
    }

    @Test
    void sameIgnoringTimeComparesEverythingButTheTimestamp() {
        DeviceState base = new DeviceState(DeviceStatus.DISCONNECTED, false, null, 0, 0, false, Instant.EPOCH);

        assertThat(DeviceState.initial().sameIgnoringTime(DeviceState.initial().withStatus(DeviceStatus.DISCONNECTED))).isTrue();
        assertThat(base.sameIgnoringTime(base.withStatus(DeviceStatus.DISCONNECTED))).isTrue();
        assertThat(base.sameIgnoringTime(null)).isFalse();
        assertThat(base.sameIgnoringTime(base.withStatus(DeviceStatus.CONNECTED))).isFalse();
        assertThat(base.sameIgnoringTime(base.withPower(true))).isFalse();
        assertThat(base.sameIgnoringTime(base.withCurrentApp("netflix"))).isFalse();
        assertThat(base.sameIgnoringTime(base.withVolume(1, 0, false))).isFalse();
        assertThat(base.sameIgnoringTime(base.withVolume(0, 100, false))).isFalse();
        assertThat(base.sameIgnoringTime(base.withVolume(0, 0, true))).isFalse();
        assertThat(base.sameIgnoringTime(base.withNowPlaying(BUNNY))).isFalse();
    }
}

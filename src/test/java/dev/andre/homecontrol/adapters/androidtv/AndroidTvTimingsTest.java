package dev.andre.homecontrol.adapters.androidtv;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class AndroidTvTimingsTest {

    @Test
    void fromTakesTheConfiguredSeconds() {
        AndroidTvProperties properties = new AndroidTvProperties(true, "shield", Duration.ofSeconds(10), Duration.ofSeconds(1), Duration.ofSeconds(4));

        assertThat(AndroidTvTimings.from(properties)).isEqualTo(
                new AndroidTvTimings(Duration.ofSeconds(10), Duration.ofSeconds(1), Duration.ofSeconds(4)));
    }
}

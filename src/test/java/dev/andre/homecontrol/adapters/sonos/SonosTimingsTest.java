package dev.andre.homecontrol.adapters.sonos;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class SonosTimingsTest {

    @Test
    void fromTakesTheConfiguredSeconds() {
        assertThat(SonosTimings.from(new SonosProperties(true, 2, 10, 30, 5, 3, 1, 60))).isEqualTo(new SonosTimings(
                Duration.ofSeconds(2), Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofSeconds(5),
                Duration.ofSeconds(1), Duration.ofSeconds(60)));
    }
}

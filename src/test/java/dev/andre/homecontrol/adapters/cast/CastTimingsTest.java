package dev.andre.homecontrol.adapters.cast;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class CastTimingsTest {

    @Test
    void fromTakesTheConfiguredSeconds() {
        CastProperties properties = new CastProperties(true, 5, 15, 1, 60, 5, 20, 5);

        assertThat(CastTimings.from(properties)).isEqualTo(new CastTimings(Duration.ofSeconds(5), Duration.ofSeconds(15),
                Duration.ofSeconds(1), Duration.ofSeconds(60), Duration.ofSeconds(5), Duration.ofSeconds(20),
                Duration.ofSeconds(5), Duration.ofMillis(750)));
    }
}

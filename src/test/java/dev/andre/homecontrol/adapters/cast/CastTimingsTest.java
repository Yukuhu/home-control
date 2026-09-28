package dev.andre.homecontrol.adapters.cast;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class CastTimingsTest {

    @Test
    void fromTakesTheConfiguredSeconds() {
        CastProperties properties = new CastProperties(true, Duration.ofSeconds(5), Duration.ofSeconds(17),
                Duration.ofSeconds(2), Duration.ofSeconds(63), Duration.ofSeconds(7), Duration.ofSeconds(23),
                Duration.ofSeconds(9));

        assertThat(CastTimings.from(properties)).isEqualTo(new CastTimings(Duration.ofSeconds(5),
                Duration.ofSeconds(17),
                Duration.ofSeconds(2), Duration.ofSeconds(63), Duration.ofSeconds(7), Duration.ofSeconds(23),
                Duration.ofSeconds(9), Duration.ofMillis(750)));
    }
}

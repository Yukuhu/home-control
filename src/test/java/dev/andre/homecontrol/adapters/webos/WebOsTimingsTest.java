package dev.andre.homecontrol.adapters.webos;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class WebOsTimingsTest {

    @Test
    void fromTakesTheConfiguredSeconds() {
        WebOsProperties properties = new WebOsProperties(true, 3000, 3001, Duration.ofSeconds(4),
                Duration.ofSeconds(11), Duration.ofSeconds(61), Duration.ofSeconds(2), Duration.ofSeconds(32),
                Duration.ofSeconds(5), Duration.ofSeconds(34));

        assertThat(WebOsTimings.from(properties)).isEqualTo(new WebOsTimings(Duration.ofSeconds(2),
                Duration.ofSeconds(32),
                Duration.ofSeconds(5), Duration.ofSeconds(34), Duration.ofSeconds(11)));
    }
}

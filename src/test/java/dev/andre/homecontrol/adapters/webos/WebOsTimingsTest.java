package dev.andre.homecontrol.adapters.webos;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class WebOsTimingsTest {

    @Test
    void fromTakesTheConfiguredSeconds() {
        WebOsProperties properties = new WebOsProperties(true, 3000, 3001, 3, 10, 60, 1, 30, 3, 30);

        assertThat(WebOsTimings.from(properties)).isEqualTo(new WebOsTimings(Duration.ofSeconds(1), Duration.ofSeconds(30),
                Duration.ofSeconds(3), Duration.ofSeconds(30), Duration.ofSeconds(10)));
    }
}

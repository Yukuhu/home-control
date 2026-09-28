package dev.andre.homecontrol.adapters.webos;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class WebOsTimingsTest {

    @Test
    void fromTakesTheConfiguredSeconds() {
        WebOsProperties properties = new WebOsProperties(true, 3000, 3001, 4, 11, 61, 2, 32, 5, 34);

        assertThat(WebOsTimings.from(properties)).isEqualTo(new WebOsTimings(Duration.ofSeconds(2), Duration.ofSeconds(32),
                Duration.ofSeconds(5), Duration.ofSeconds(34), Duration.ofSeconds(11)));
    }
}

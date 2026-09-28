package dev.andre.homecontrol.adapters.upnp;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class UpnpTimingsTest {

    @Test
    void fromTakesTheConfiguredSeconds() {
        assertThat(UpnpTimings.from(new UpnpProperties(true, 2, 10, 5, 3, 1, 60))).isEqualTo(new UpnpTimings(
                Duration.ofSeconds(2), Duration.ofSeconds(10), Duration.ofSeconds(5), Duration.ofSeconds(1),
                Duration.ofSeconds(60)));
    }
}

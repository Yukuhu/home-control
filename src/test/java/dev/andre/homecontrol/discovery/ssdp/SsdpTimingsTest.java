package dev.andre.homecontrol.discovery.ssdp;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class SsdpTimingsTest {

    @Test
    void fromTakesTheConfiguredSeconds() {
        SsdpProperties properties = new SsdpProperties(true, "239.255.255.250", 1900, 1900, 60, 2);

        assertThat(SsdpTimings.from(properties)).isEqualTo(new SsdpTimings(Duration.ofSeconds(60)));
    }
}

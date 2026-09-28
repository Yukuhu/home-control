package dev.andre.homecontrol.adapters.tizen;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class TizenTimingsTest {

    @Test
    void fromTakesTheConfiguredSecondsAndCapsTheHandshakeBackoffAtFiveMinutes() {
        TizenProperties properties = new TizenProperties(true, 8002, 8001, 8080, "Home Control",
                Duration.ofSeconds(4), Duration.ofSeconds(6), Duration.ofSeconds(30), Duration.ofSeconds(7),
                Duration.ofSeconds(2));

        assertThat(TizenTimings.from(properties)).isEqualTo(new TizenTimings(Duration.ofSeconds(7),
                Duration.ofSeconds(2),
                Duration.ofSeconds(6), Duration.ofMinutes(5)));
    }
}

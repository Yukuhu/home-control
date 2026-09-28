package dev.andre.homecontrol.adapters.tizen;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class TizenTimingsTest {

    @Test
    void fromTakesTheConfiguredSecondsAndCapsTheHandshakeBackoffAtFiveMinutes() {
        TizenProperties properties = new TizenProperties(true, 8002, 8001, 8080, "Home Control", 3, 5, 30, 5, 3);

        assertThat(TizenTimings.from(properties)).isEqualTo(new TizenTimings(Duration.ofSeconds(5), Duration.ofSeconds(3),
                Duration.ofSeconds(5), Duration.ofMinutes(5)));
    }
}

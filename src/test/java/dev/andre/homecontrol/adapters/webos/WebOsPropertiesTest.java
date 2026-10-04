package dev.andre.homecontrol.adapters.webos;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebOsPropertiesTest {

    @Test
    void theLongestReconnectWaitMayNotBeShorterThanTheFirst() {
        Duration second = Duration.ofSeconds(1);
        Duration tenSeconds = Duration.ofSeconds(10);
        Duration fiveSeconds = Duration.ofSeconds(5);

        assertThatThrownBy(() -> new WebOsProperties(true, 3000, 3001, second, second, second, tenSeconds,
                fiveSeconds, second))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("home-control.webos.reconnect-max-delay (")
                .hasMessageContaining("must not be less than home-control.webos.reconnect-initial-delay");
    }

    @Test
    void equalReconnectWaitsAreAllowedAndTheLivenessCheckDefaultsToThirtySeconds() {
        Duration second = Duration.ofSeconds(1);

        WebOsProperties properties = new WebOsProperties(true, 3000, 3001, second, second, second, second, second,
                second);

        assertThat(properties.livenessInterval()).isEqualTo(Duration.ofSeconds(30));
    }
}

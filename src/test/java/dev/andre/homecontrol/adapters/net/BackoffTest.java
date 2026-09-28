package dev.andre.homecontrol.adapters.net;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class BackoffTest {

    @Test
    void backoffDoublesUpToItsCap() {
        Duration max = Duration.ofMillis(300);

        assertThat(Backoff.next(Duration.ofMillis(50), max)).isEqualTo(Duration.ofMillis(100));
        assertThat(Backoff.next(Duration.ofMillis(100), max)).isEqualTo(Duration.ofMillis(200));
        assertThat(Backoff.next(Duration.ofMillis(200), max)).isEqualTo(max);
        assertThat(Backoff.next(max, max)).isEqualTo(max);
        assertThat(Backoff.next(Duration.ofMillis(1500), Duration.ofSeconds(60))).isEqualTo(Duration.ofSeconds(3));
    }
}

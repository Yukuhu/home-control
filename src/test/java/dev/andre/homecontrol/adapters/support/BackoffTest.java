package dev.andre.homecontrol.adapters.support;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BackoffTest {

    @Test
    void startsAtTheInitialDelayAndDoublesUpToTheMaximum() {
        Backoff backoff = new Backoff(Duration.ofSeconds(1), Duration.ofSeconds(5));

        assertThat(List.of(backoff.next(), backoff.next(), backoff.next(), backoff.next(), backoff.next()))
                .containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4),
                        Duration.ofSeconds(5), Duration.ofSeconds(5));
    }

    @Test
    void resetStartsOverAtTheInitialDelay() {
        Backoff backoff = new Backoff(Duration.ofSeconds(1), Duration.ofSeconds(60));
        backoff.next();
        backoff.next();

        backoff.reset();

        assertThat(backoff.next()).isEqualTo(Duration.ofSeconds(1));
    }
}

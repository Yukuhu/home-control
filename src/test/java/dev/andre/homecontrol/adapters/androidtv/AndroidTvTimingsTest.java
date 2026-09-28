package dev.andre.homecontrol.adapters.androidtv;

import dev.andre.homecontrol.adapters.net.Backoff;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class AndroidTvTimingsTest {

    @Test
    void fromTakesTheConfiguredSeconds() {
        AndroidTvProperties properties = new AndroidTvProperties(Path.of("data"), "shield", false, 10, 1, 4);

        assertThat(AndroidTvTimings.from(properties)).isEqualTo(
                new AndroidTvTimings(Duration.ofSeconds(10), Duration.ofSeconds(1), Duration.ofSeconds(4)));
    }

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

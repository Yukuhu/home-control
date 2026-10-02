package dev.andre.homecontrol.adapters.support;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class DurationTextTest {

    @Test
    void wholeUnitsAreWords() {
        assertThat(DurationText.of(Duration.ofSeconds(60))).isEqualTo("1 minute");
        assertThat(DurationText.of(Duration.ofSeconds(30))).isEqualTo("30 seconds");
        assertThat(DurationText.of(Duration.ofSeconds(1))).isEqualTo("1 second");
        assertThat(DurationText.of(Duration.ofMinutes(2))).isEqualTo("2 minutes");
        assertThat(DurationText.of(Duration.ofHours(1))).isEqualTo("1 hour");
    }

    @Test
    void mixedUnitsAreListed() {
        assertThat(DurationText.of(Duration.ofSeconds(90))).isEqualTo("1 minute 30 seconds");
        assertThat(DurationText.of(Duration.ofMinutes(61))).isEqualTo("1 hour 1 minute");
    }

    @Test
    void lessThanASecondOrAFractionIsInMilliseconds() {
        assertThat(DurationText.of(Duration.ofMillis(500))).isEqualTo("500 milliseconds");
        assertThat(DurationText.of(Duration.ofMillis(1500))).isEqualTo("1500 milliseconds");
        assertThat(DurationText.of(Duration.ZERO)).isEqualTo("0 seconds");
    }
}

package dev.andre.homecontrol.testsupport;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class MutableClockTest {

    private static final Instant START = Instant.parse("2026-09-16T12:00:00Z");

    @Test
    void standsStillUntilAdvanced() {
        MutableClock clock = MutableClock.at(START);

        assertThat(clock.instant()).isEqualTo(START);
        clock.advance(Duration.ofMinutes(30));
        assertThat(clock.instant()).isEqualTo(START.plus(Duration.ofMinutes(30)));
    }

    @Test
    void atIsInUtcAndTheConstructorKeepsItsZone() {
        assertThat(MutableClock.at(START).getZone()).isEqualTo(ZoneId.of("UTC"));
        assertThat(new MutableClock(START, ZoneOffset.UTC).getZone()).isEqualTo(ZoneOffset.UTC);
    }

    @Test
    void withZoneKeepsTheInstant() {
        MutableClock clock = MutableClock.at(START);

        assertThat(clock.withZone(ZoneId.of("America/Los_Angeles")).instant()).isEqualTo(START);
    }
}

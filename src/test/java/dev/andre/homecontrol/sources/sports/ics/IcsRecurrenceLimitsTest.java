package dev.andre.homecontrol.sources.sports.ics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** The numeric bounds and WKST check of the supported RRULE subset. */
class IcsRecurrenceLimitsTest {

    @Test
    void acceptsTheLargestIntervalAndCount() {
        IcsRecurrence rule = IcsRecurrence.parse("FREQ=DAILY;INTERVAL=1000;COUNT=10000;WKST=MO").orElseThrow();

        assertThat(rule.interval()).isEqualTo(1000);
        assertThat(rule.count()).isEqualTo(10_000);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "FREQ=DAILY;INTERVAL=1001", "FREQ=DAILY;COUNT=0", "FREQ=DAILY;COUNT=10001", "FREQ=WEEKLY;WKST=XX",
            "FREQ=WEEKLY;BYDAY=MO,XX", "=DAILY"
    })
    void rejectsValuesOutsideTheBounds(String rule) {
        assertThat(IcsRecurrence.parse(rule)).isEmpty();
    }
}

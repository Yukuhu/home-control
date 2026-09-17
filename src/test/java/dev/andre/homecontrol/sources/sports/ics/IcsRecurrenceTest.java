package dev.andre.homecontrol.sources.sports.ics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.DayOfWeek;

import static org.assertj.core.api.Assertions.assertThat;

class IcsRecurrenceTest {

    @Test
    void parsesTheSupportedSubset() {
        IcsRecurrence rule = IcsRecurrence.parse("FREQ=WEEKLY;INTERVAL=2;BYDAY=SA,TH,SA;WKST=SU;COUNT=5").orElseThrow();
        assertThat(rule.frequency()).isEqualTo(IcsRecurrence.Frequency.WEEKLY);
        assertThat(rule.interval()).isEqualTo(2);
        assertThat(rule.count()).isEqualTo(5);
        assertThat(rule.until()).isNull();
        assertThat(rule.byDay()).containsExactly(DayOfWeek.THURSDAY, DayOfWeek.SATURDAY);

        IcsRecurrence daily = IcsRecurrence.parse("freq=daily;until=20260920").orElseThrow();
        assertThat(daily.frequency()).isEqualTo(IcsRecurrence.Frequency.DAILY);
        assertThat(daily.interval()).isEqualTo(1);
        assertThat(daily.count()).isNull();
        assertThat(daily.until()).isEqualTo(new IcsTime.Date(java.time.LocalDate.of(2026, 9, 20)));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "FREQ=MONTHLY;BYDAY=1SA", "FREQ=YEARLY", "FREQ=WEEKLY;BYDAY=1MO", "FREQ=DAILY;BYDAY=MO",
            "FREQ=WEEKLY;COUNT=2;UNTIL=20261231", "INTERVAL=2", "FREQ=WEEKLY;BYMONTH=9",
            "FREQ=WEEKLY;INTERVAL=0", "FREQ=WEEKLY;COUNT=abc", "FREQ=WEEKLY;UNTIL=soon", "FREQ=WEEKLY;;X"
    })
    void rejectsEverythingElse(String rule) {
        assertThat(IcsRecurrence.parse(rule)).isEmpty();
    }
}

package dev.andre.homecontrol.sources.sports.ics;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

/** Event lengths and overrides the fixtures do not exercise. */
class IcsOccurrencesEdgeCaseTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final Duration DEFAULT_DURATION = Duration.ofMinutes(120);

    private static IcsOccurrences.Result expand(String... lines) {
        IcsCalendar calendar = IcsParser.parse("BEGIN:VCALENDAR\n" + String.join("\n", lines) + "\nEND:VCALENDAR\n");
        return IcsOccurrences.expand(calendar, BERLIN, Instant.parse("2026-09-18T00:00:00Z"),
                Instant.parse("2026-09-30T00:00:00Z"), DEFAULT_DURATION);
    }

    private static IcsOccurrence only(IcsOccurrences.Result result) {
        assertThat(result.occurrences()).hasSize(1);
        return result.occurrences().getFirst();
    }

    @Test
    void anAllDayEventWithADurationSpansThoseDays() {
        IcsOccurrence stage = only(expand(
                "BEGIN:VEVENT", "UID:tour@x", "SUMMARY:Stage race", "DTSTART;VALUE=DATE:20260919", "DURATION:P3D",
                "END:VEVENT"));

        assertThat(stage.allDayDate()).isEqualTo(LocalDate.of(2026, 9, 19));
        assertThat(stage.startsAt()).isEqualTo(Instant.parse("2026-09-18T22:00:00Z"));
        assertThat(stage.endsAt()).isEqualTo(Instant.parse("2026-09-21T22:00:00Z"));
    }

    @Test
    void anEventEndingBeforeItStartsGetsTheDefaultLength() {
        IcsOccurrence backwards = only(expand(
                "BEGIN:VEVENT", "UID:backwards@x", "DTSTART:20260919T100000Z", "DTEND:20260919T090000Z", "END:VEVENT"));

        assertThat(backwards.startsAt()).isEqualTo(Instant.parse("2026-09-19T10:00:00Z"));
        assertThat(backwards.endsAt()).isEqualTo(Instant.parse("2026-09-19T12:00:00Z"));
        assertThat(backwards.summary()).isEmpty();
    }

    @Test
    void anOverrideWithoutUidReplacesNothing() {
        IcsOccurrences.Result result = expand(
                "BEGIN:VEVENT", "SUMMARY:Series", "DTSTART:20260919T100000Z", "RRULE:FREQ=DAILY;COUNT=2", "END:VEVENT",
                "BEGIN:VEVENT", "SUMMARY:Moved", "RECURRENCE-ID:20260920T100000Z", "DTSTART:20260920T150000Z",
                "END:VEVENT");

        assertThat(result.occurrences()).extracting(IcsOccurrence::summary, IcsOccurrence::startsAt).containsExactly(
                tuple("Series", Instant.parse("2026-09-19T10:00:00Z")),
                tuple("Series", Instant.parse("2026-09-20T10:00:00Z")),
                tuple("Moved", Instant.parse("2026-09-20T15:00:00Z")));
    }
}

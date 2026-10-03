package dev.andre.homecontrol.sources.sports.ics;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

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
    void oneCalendarsExpansionHasOneBudgetForAllItsEvents() {
        // Each event steps day by day from 1900 to the window, near the cap of one event; twenty of them are
        // hostile, and stop once the calendar's budget is spent rather than each spending its own.
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            lines.addAll(List.of("BEGIN:VEVENT", "UID:daily-" + i + "@x", "SUMMARY:Daily " + i,
                    "DTSTART:19000101T120000Z", "RRULE:FREQ=DAILY", "END:VEVENT"));
        }

        List<IcsOccurrence> occurrences = expand(lines.toArray(String[]::new)).occurrences();

        assertThat(occurrences).isNotEmpty();
        assertThat(occurrences.stream().map(IcsOccurrence::uid).distinct().count()).isLessThan(20);
    }

    @Test
    void theWeekStartDecidesWhichWeeksAnIntervalSkips() {
        // RFC 5545's own example: every other week on Sunday and Monday, counted in weeks from Sunday or from Monday.
        String[] fromSunday = {"BEGIN:VEVENT", "UID:alt@x", "SUMMARY:Alternating", "DTSTART:20260920T130000Z",
                "RRULE:FREQ=WEEKLY;INTERVAL=2;BYDAY=SU,MO;WKST=SU", "END:VEVENT"};
        String[] fromMonday = {"BEGIN:VEVENT", "UID:alt@x", "SUMMARY:Alternating", "DTSTART:20260920T130000Z",
                "RRULE:FREQ=WEEKLY;INTERVAL=2;BYDAY=SU,MO;WKST=MO", "END:VEVENT"};

        assertThat(expand(fromSunday).occurrences()).extracting(IcsOccurrence::startsAt).containsExactly(
                Instant.parse("2026-09-20T13:00:00Z"), Instant.parse("2026-09-21T13:00:00Z"));
        assertThat(expand(fromMonday).occurrences()).extracting(IcsOccurrence::startsAt).containsExactly(
                Instant.parse("2026-09-20T13:00:00Z"), Instant.parse("2026-09-28T13:00:00Z"));
    }

    @Test
    void theWeekStartDecidesWhichOccurrencesACountReaches() {
        // RFC 5545's example, moved to 2026: from a Tuesday, four occurrences every other week on Tuesday and Sunday.
        String rule = "RRULE:FREQ=WEEKLY;INTERVAL=2;COUNT=4;BYDAY=TU,SU;WKST=";

        assertThat(startsOf(rule + "MO")).containsExactly(Instant.parse("2026-09-22T13:00:00Z"),
                Instant.parse("2026-09-27T13:00:00Z"), Instant.parse("2026-10-06T13:00:00Z"),
                Instant.parse("2026-10-11T13:00:00Z"));
        assertThat(startsOf(rule + "SU")).containsExactly(Instant.parse("2026-09-22T13:00:00Z"),
                Instant.parse("2026-10-04T13:00:00Z"), Instant.parse("2026-10-06T13:00:00Z"),
                Instant.parse("2026-10-18T13:00:00Z"));
    }

    private static List<Instant> startsOf(String rule) {
        IcsCalendar calendar = IcsParser.parse("BEGIN:VCALENDAR\nBEGIN:VEVENT\nUID:count@x\nSUMMARY:Counted\n"
                + "DTSTART:20260922T130000Z\n" + rule + "\nEND:VEVENT\nEND:VCALENDAR\n");
        return IcsOccurrences.expand(calendar, BERLIN, Instant.parse("2026-09-18T00:00:00Z"),
                Instant.parse("2026-10-31T00:00:00Z"), DEFAULT_DURATION).occurrences().stream()
                .map(IcsOccurrence::startsAt).toList();
    }

    @Test
    void anAllDayEventIsPlacedOnItsDateInTheHouseholdsZone() {
        // A calendar kept in New York: its "20 September" is still 20 September in the household in Berlin.
        IcsCalendar calendar = IcsParser.parse("""
                BEGIN:VCALENDAR
                X-WR-TIMEZONE:America/New_York
                BEGIN:VEVENT
                UID:matchday@x
                SUMMARY:Matchday
                DTSTART;VALUE=DATE:20260920
                END:VEVENT
                END:VCALENDAR
                """);

        IcsOccurrence matchday = IcsOccurrences.expand(calendar, BERLIN, Instant.parse("2026-09-18T00:00:00Z"),
                Instant.parse("2026-09-30T00:00:00Z"), DEFAULT_DURATION).occurrences().getFirst();

        assertThat(matchday.startsAt()).isEqualTo(Instant.parse("2026-09-19T22:00:00Z"));
        assertThat(matchday.endsAt()).isEqualTo(Instant.parse("2026-09-20T22:00:00Z"));
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

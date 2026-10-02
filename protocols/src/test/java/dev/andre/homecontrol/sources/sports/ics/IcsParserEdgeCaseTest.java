package dev.andre.homecontrol.sources.sports.ics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Content-line, component-nesting and property edge cases of the RFC 5545 subset reader. */
class IcsParserEdgeCaseTest {

    private static IcsCalendar calendar(String... lines) {
        return IcsParser.parse("BEGIN:VCALENDAR\n" + String.join("\n", lines) + "\nEND:VCALENDAR\n");
    }

    @ParameterizedTest
    @ValueSource(strings = {"NO-COLON", ":value", " ;X=1:value", "X;P=\"unterminated:value"})
    void linesWithoutANameOrValueAreNotContentLines(String raw) {
        assertThat(IcsParser.contentLine(raw)).isNull();
    }

    @Test
    void readsParametersFirstOneWinsAndQuotesAreRemoved() {
        IcsParser.ContentLine line = IcsParser.contentLine(
                "dtstart;VALUE;=x;tzid=Europe/Berlin;TZID=Europe/London;X-EMPTY=\"\";X-ODD=\"a\"b:20260919T100000");

        assertThat(line.name()).isEqualTo("DTSTART");
        assertThat(line.value()).isEqualTo("20260919T100000");
        assertThat(line.params()).isEqualTo(Map.of("TZID", "Europe/Berlin", "X-EMPTY", "", "X-ODD", "\"a\"b"));
    }

    @Test
    void theFirstReadableSingleValuedPropertyWins() {
        IcsCalendar calendar = calendar(
                "BEGIN:VEVENT",
                "UID: ",
                "UID:first@x",
                "UID:second@x",
                "SUMMARY:First",
                "SUMMARY:Second",
                "DTSTART:20260919T100000Z",
                "DTSTART:not a time",
                "DTEND:20260919T120000Z",
                "DTEND:not a time",
                "DURATION:nonsense",
                "DURATION:PT1H",
                "DURATION:PT2H",
                "RRULE: FREQ=DAILY;COUNT=2 ",
                "RRULE:FREQ=WEEKLY",
                "RECURRENCE-ID:20260918T100000Z",
                "RECURRENCE-ID:not a time",
                "STATUS:tentative",
                "STATUS:confirmed",
                "EXDATE:20260920T100000Z,,20260921T100000Z",
                "X-UNKNOWN:ignored",
                "END:VEVENT");

        assertThat(calendar.skippedEvents()).isZero();
        IcsEvent event = calendar.events().getFirst();
        assertThat(event.uid()).isEqualTo("first@x");
        assertThat(event.summary()).isEqualTo("First");
        assertThat(event.start()).isEqualTo(new IcsTime.Utc(Instant.parse("2026-09-19T10:00:00Z")));
        assertThat(event.end()).isEqualTo(new IcsTime.Utc(Instant.parse("2026-09-19T12:00:00Z")));
        assertThat(event.duration()).isEqualTo(Duration.ofHours(1));
        assertThat(event.rrule()).isEqualTo("FREQ=DAILY;COUNT=2");
        assertThat(event.recurrenceId()).isEqualTo(new IcsTime.Utc(Instant.parse("2026-09-18T10:00:00Z")));
        assertThat(event.status()).isEqualTo("CONFIRMED");
        assertThat(event.exdates()).containsExactly(
                new IcsTime.Utc(Instant.parse("2026-09-20T10:00:00Z")),
                new IcsTime.Utc(Instant.parse("2026-09-21T10:00:00Z")));
    }

    @Test
    void onlyEventsDirectlyInsideTheCalendarAreRead() {
        IcsCalendar calendar = calendar(
                "BEGIN:X-WRAPPER",
                "X-WR-CALNAME:Not the calendar name",
                "BEGIN:VEVENT",
                "DTSTART:20260919T100000Z",
                "END:VEVENT",
                "END:X-WRAPPER",
                "BEGIN:VEVENT",
                "DTSTART;VALUE=DATE:20260919",
                "BEGIN:VALARM",
                "SUMMARY:Reminder",
                "END:VALARM",
                "SUMMARY:Kick-off",
                "END:VEVENT");

        assertThat(calendar.name()).isNull();
        assertThat(calendar.skippedEvents()).isZero();
        assertThat(calendar.events()).singleElement().satisfies(event -> {
            assertThat(event.summary()).isEqualTo("Kick-off");
            assertThat(event.start()).isEqualTo(new IcsTime.Date(LocalDate.of(2026, 9, 19)));
        });
    }

    @Test
    void strayEndLinesAndBlankCalendarPropertiesAreIgnored() {
        IcsCalendar calendar = IcsParser.parse(
                "BEGIN:VCALENDAR\nX-WR-CALNAME: \nX-WR-TIMEZONE:  \n\n   \nEND:VCALENDAR\nEND:VCALENDAR\nEND:VEVENT\n");

        assertThat(calendar.name()).isNull();
        assertThat(calendar.timeZone()).isNull();
        assertThat(calendar.events()).isEmpty();
        assertThat(calendar.skippedEvents()).isZero();
    }

    @Test
    void anUnclosedEventIsSkipped() {
        IcsCalendar calendar = IcsParser.parse("BEGIN:VCALENDAR\nBEGIN:VEVENT\nDTSTART:20260919T100000Z\n");

        assertThat(calendar.events()).isEmpty();
        assertThat(calendar.skippedEvents()).isEqualTo(1);
    }

    @Test
    void floatingTimesKeepNoZone() {
        IcsCalendar calendar = calendar("BEGIN:VEVENT", "DTSTART;TZID= :20260919T183000", "END:VEVENT");

        assertThat(calendar.events().getFirst().start())
                .isEqualTo(new IcsTime.Local(LocalDateTime.of(2026, 9, 19, 18, 30), null));
    }

    @ParameterizedTest
    @CsvSource({
            "+PT5M, 300",
            "P1DT, 86400",
            "pt1h30m, 5400",
            "' PT1H30M15S ', 5415",
            "P1W2DT3H4M, 788640",
    })
    void readsEveryDurationShape(String value, long expectedSeconds) {
        assertThat(IcsParser.parseDuration(value)).isEqualTo(Duration.ofSeconds(expectedSeconds));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PT1HT", "T1H", "P1D1W", "PT1S1M", "PT1X", "P1Y", "PTT"})
    void rejectsDurationsOutOfOrderOrWithUnknownUnits(String value) {
        assertThat(IcsParser.parseDuration(value)).isNull();
    }

    @Test
    void aNullDurationIsNull() {
        assertThat(IcsParser.parseDuration(null)).isNull();
    }
}

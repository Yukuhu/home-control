package dev.andre.homecontrol.sources.sports.ics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IcsParserTest {

    private static String fixture(String name) {
        try (InputStream in = IcsParserTest.class.getResourceAsStream("/fixtures/ics/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void unfoldsContinuationLines() {
        assertThat(IcsParser.unfold("A:1\n  two\n\tthree\nB:2")).containsExactly("A:1 twothree", "B:2");
        assertThat(IcsParser.unfold("A:1\r\n  two\r\nB:2")).containsExactly("A:1 two", "B:2");
        assertThat(IcsParser.unfold("A:1\r  two\rB:2")).containsExactly("A:1 two", "B:2");
    }

    @Test
    void unescapesText() {
        assertThat(IcsParser.unescape("a\\,b")).isEqualTo("a,b");
        assertThat(IcsParser.unescape("a\\;b")).isEqualTo("a;b");
        assertThat(IcsParser.unescape("a\\\\b")).isEqualTo("a\\b");
        assertThat(IcsParser.unescape("a\\xb")).isEqualTo("axb");
    }

    @Test
    void unescapesNewlines() {
        assertThat(IcsParser.unescape("a\\nb")).isEqualTo("a\nb");
        assertThat(IcsParser.unescape("a\\Nb")).isEqualTo("a\nb");
    }

    @Test
    void trailingBackslashStays() {
        assertThat(IcsParser.unescape("a\\")).isEqualTo("a\\");
    }

    @Test
    void parsesTimes() {
        assertThat(IcsParser.parseTime("20260919", null)).isEqualTo(new IcsTime.Date(LocalDate.of(2026, 9, 19)));
        assertThat(IcsParser.parseTime("20260919T133000Z", null))
                .isEqualTo(new IcsTime.Utc(java.time.Instant.parse("2026-09-19T13:30:00Z")));
        assertThat(IcsParser.parseTime("20260919t133000z", null))
                .isEqualTo(new IcsTime.Utc(java.time.Instant.parse("2026-09-19T13:30:00Z")));
        assertThat(IcsParser.parseTime("20260919T183000", "Europe/Berlin"))
                .isEqualTo(new IcsTime.Local(LocalDateTime.of(2026, 9, 19, 18, 30), "Europe/Berlin"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"20260231", "20260919T240000", "2026-09-19", ""})
    void rejectsUnreadableTimes(String value) {
        assertThatThrownBy(() -> IcsParser.parseTime(value, null))
                .isInstanceOf(IcsFormatException.class)
                .hasMessage("Unreadable date or time");
    }

    @ParameterizedTest
    @CsvSource({
            "PT1H55M, 115",
            "P1D, 1440",
            "P1W, 10080",
            "P1DT2H, 1560",
    })
    void parsesDurations(String value, long expectedMinutes) {
        assertThat(IcsParser.parseDuration(value)).isEqualTo(Duration.ofMinutes(expectedMinutes));
    }

    @ParameterizedTest
    @ValueSource(strings = {"-PT1H", "P", "PT", "PT0S", "nonsense", "PT99999999999999999999H"})
    void invalidDurationsAreNull(String value) {
        assertThat(IcsParser.parseDuration(value)).isNull();
    }

    @Test
    void readsTheBundesligaFixture() {
        IcsCalendar calendar = IcsParser.parse(fixture("bundesliga.ics"));

        assertThat(calendar.name()).isEqualTo("Bundesliga 2026/27");
        assertThat(calendar.timeZone()).isEqualTo("Europe/Berlin");
        assertThat(calendar.events()).hasSize(5);
        assertThat(calendar.skippedEvents()).isZero();

        IcsEvent event2 = calendar.events().get(1);
        assertThat(event2.start()).isInstanceOf(IcsTime.Utc.class);
        assertThat(event2.end()).isInstanceOf(IcsTime.Utc.class);

        IcsEvent event3 = calendar.events().get(2);
        assertThat(event3.start()).isEqualTo(new IcsTime.Local(LocalDateTime.of(2026, 9, 19, 18, 30), "Europe/Berlin"));
        assertThat(event3.duration()).isEqualTo(Duration.ofMinutes(115));
        assertThat(event3.end()).isNull();

        IcsEvent event4 = calendar.events().get(3);
        assertThat(event4.summary()).isEqualTo("VfB Stuttgart – SC Freiburg, Topspiel des 4. Spieltags; live im Stream");
        assertThat(event4.end()).isNull();
        assertThat(event4.duration()).isNull();

        IcsEvent event5 = calendar.events().get(4);
        assertThat(event5.status()).isEqualTo("CANCELLED");

        assertThat(calendar.events()).allMatch(e -> e.rrule() == null);
    }

    @Test
    void toleratesWindowsLineEndingsAndABom() {
        String withCrlf = "﻿" + fixture("outlook.ics").replace("\n", "\r\n");
        IcsCalendar calendar = IcsParser.parse(withCrlf);

        assertThat(calendar.events()).hasSize(3);
        IcsEvent first = calendar.events().getFirst();
        assertThat(first.summary()).isEqualTo("Handball: Finale");
        assertThat(first.start()).isEqualTo(new IcsTime.Local(LocalDateTime.of(2026, 9, 19, 18, 0), "W. Europe Standard Time"));
    }

    @Test
    void skipsBrokenEvents() {
        IcsCalendar calendar = IcsParser.parse(fixture("broken.ics"));

        assertThat(calendar.events()).hasSize(1);
        assertThat(calendar.events().getFirst().summary()).isEqualTo("No uid but fine");
        assertThat(calendar.events().getFirst().uid()).isNull();
        assertThat(calendar.skippedEvents()).isEqualTo(3);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "BEGIN:VEVENT\nEND:VEVENT"})
    void refusesWhatIsNotACalendar(String text) {
        assertThatThrownBy(() -> IcsParser.parse(text))
                .isInstanceOf(IcsFormatException.class)
                .hasMessage("That link did not return a calendar (.ics)");
    }

    @Test
    void refusesHtml() {
        assertThatThrownBy(() -> IcsParser.parse(fixture("not-a-calendar.html")))
                .isInstanceOf(IcsFormatException.class)
                .hasMessage("That link did not return a calendar (.ics)");
    }

    @Test
    void readsRecurrenceProperties() {
        IcsCalendar calendar = IcsParser.parse(fixture("recurring.ics"));

        IcsEvent mnf = calendar.events().stream().filter(e -> "mnf@fixtures.example".equals(e.uid())).findFirst().orElseThrow();
        assertThat(mnf.rrule()).isEqualTo("FREQ=WEEKLY;COUNT=4");
        assertThat(mnf.exdates()).containsExactly(new IcsTime.Local(LocalDateTime.of(2026, 9, 21, 20, 15), "America/New_York"));

        IcsEvent moved = calendar.events().stream()
                .filter(e -> "darts@fixtures.example".equals(e.uid()) && e.recurrenceId() != null).findFirst().orElseThrow();
        assertThat(moved.recurrenceId()).isEqualTo(new IcsTime.Local(LocalDateTime.of(2026, 9, 19, 19, 0), "Europe/London"));

        IcsEvent vuelta = calendar.events().stream().filter(e -> "vuelta@fixtures.example".equals(e.uid())).findFirst().orElseThrow();
        assertThat(vuelta.start()).isInstanceOf(IcsTime.Date.class);
    }

    @Test
    void capsTheNumberOfEvents() {
        StringBuilder text = new StringBuilder("BEGIN:VCALENDAR\nVERSION:2.0\n");
        for (int i = 0; i < 5001; i++) {
            text.append("BEGIN:VEVENT\nUID:e").append(i).append("@x\nDTSTART:20260919T100000Z\nEND:VEVENT\n");
        }
        text.append("END:VCALENDAR\n");

        assertThatThrownBy(() -> IcsParser.parse(text.toString()))
                .isInstanceOf(IcsFormatException.class)
                .hasMessage("The calendar has more than 5000 events");
    }

    @Test
    void quotedParametersMayContainSeparators() {
        IcsCalendar calendar = IcsParser.parse(
                "BEGIN:VCALENDAR\nVERSION:2.0\nBEGIN:VEVENT\nUID:x\nDTSTART;TZID=\"Odd;Zone:Name\":20260919T100000\nEND:VEVENT\nEND:VCALENDAR\n");

        IcsTime.Local start = (IcsTime.Local) calendar.events().getFirst().start();
        assertThat(start.tzid()).isEqualTo("Odd;Zone:Name");
    }
}

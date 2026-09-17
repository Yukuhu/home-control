package dev.andre.homecontrol.sources.sports.ics;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IcsOccurrencesTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final Duration DEFAULT_DURATION = Duration.ofMinutes(120);

    private static String fixture(String name) {
        try (InputStream in = IcsOccurrencesTest.class.getResourceAsStream("/fixtures/ics/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void expandsTheBundesligaFixture() {
        IcsCalendar calendar = IcsParser.parse(fixture("bundesliga.ics"));
        IcsOccurrences.Result result = IcsOccurrences.expand(calendar, BERLIN,
                Instant.parse("2026-09-18T14:00:00Z"), Instant.parse("2026-09-27T14:00:00Z"), DEFAULT_DURATION);

        List<IcsOccurrence> sorted = result.occurrences().stream()
                .sorted(Comparator.comparing(IcsOccurrence::startsAt)).toList();
        assertThat(sorted).hasSize(4);
        assertThat(sorted.get(0).startsAt()).isEqualTo(Instant.parse("2026-09-18T18:30:00Z"));
        assertThat(sorted.get(0).endsAt()).isEqualTo(Instant.parse("2026-09-18T20:25:00Z"));
        assertThat(sorted.get(1).startsAt()).isEqualTo(Instant.parse("2026-09-19T13:30:00Z"));
        assertThat(sorted.get(1).endsAt()).isEqualTo(Instant.parse("2026-09-19T15:25:00Z"));
        assertThat(sorted.get(2).startsAt()).isEqualTo(Instant.parse("2026-09-19T16:30:00Z"));
        assertThat(sorted.get(2).endsAt()).isEqualTo(Instant.parse("2026-09-19T18:25:00Z"));
        assertThat(sorted.get(3).startsAt()).isEqualTo(Instant.parse("2026-09-20T15:30:00Z"));
        assertThat(sorted.get(3).endsAt()).isEqualTo(Instant.parse("2026-09-20T17:30:00Z"));
        assertThat(result.unsupportedRules()).isZero();
        assertThat(result.unknownZones()).isZero();
        assertThat(sorted).allMatch(o -> o.allDayDate() == null);
    }

    @Test
    void windowBoundsAreHalfOpen() {
        IcsCalendar calendar = IcsParser.parse(fixture("bundesliga.ics"));
        IcsOccurrences.Result result = IcsOccurrences.expand(calendar, BERLIN,
                Instant.parse("2026-09-19T15:25:00Z"), Instant.parse("2026-09-19T16:30:00Z"), DEFAULT_DURATION);

        assertThat(result.occurrences()).isEmpty();
    }

    @Test
    void expandsTheRecurringFixture() {
        IcsCalendar calendar = IcsParser.parse(fixture("recurring.ics"));
        IcsOccurrences.Result result = IcsOccurrences.expand(calendar, BERLIN,
                Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-10-10T00:00:00Z"), DEFAULT_DURATION);

        assertThat(result.occurrences()).hasSize(15);
        assertThat(result.unsupportedRules()).isEqualTo(1);

        List<Instant> mnf = result.occurrences().stream()
                .filter(o -> "Monday Night Football".equals(o.summary())).map(IcsOccurrence::startsAt).sorted().toList();
        assertThat(mnf).containsExactly(Instant.parse("2026-09-08T00:15:00Z"),
                Instant.parse("2026-09-15T00:15:00Z"), Instant.parse("2026-09-29T00:15:00Z"));

        List<Instant> darts = result.occurrences().stream()
                .filter(o -> "Darts Premier League".equals(o.summary())).map(IcsOccurrence::startsAt).sorted().toList();
        assertThat(darts).containsExactly(Instant.parse("2026-09-17T18:00:00Z"), Instant.parse("2026-09-24T18:00:00Z"),
                Instant.parse("2026-09-26T18:00:00Z"), Instant.parse("2026-10-01T18:00:00Z"), Instant.parse("2026-10-03T18:00:00Z"));

        assertThat(result.occurrences().stream().anyMatch(o -> "Darts Premier League (moved)".equals(o.summary())
                && o.startsAt().equals(Instant.parse("2026-09-19T19:00:00Z"))
                && o.endsAt().equals(Instant.parse("2026-09-19T22:30:00Z")))).isTrue();

        List<IcsOccurrence> vuelta = result.occurrences().stream()
                .filter(o -> "Vuelta stage".equals(o.summary())).sorted(Comparator.comparing(IcsOccurrence::startsAt)).toList();
        assertThat(vuelta).hasSize(4);
        assertThat(vuelta.getFirst().startsAt()).isEqualTo(Instant.parse("2026-09-16T22:00:00Z"));
        assertThat(vuelta.getFirst().allDayDate()).isNotNull();

        assertThat(result.occurrences().stream().anyMatch(o -> "Club meeting".equals(o.summary()))).isTrue();
        assertThat(result.occurrences().stream().anyMatch(o -> "Local kickoff".equals(o.summary())
                && o.startsAt().equals(Instant.parse("2026-09-19T13:00:00Z")))).isTrue();
    }

    @Test
    void wallClockSurvivesDst() {
        IcsCalendar calendar = IcsParser.parse(
                "BEGIN:VCALENDAR\nVERSION:2.0\nBEGIN:VEVENT\nUID:x\n"
                        + "DTSTART;TZID=Europe/Berlin:20261017T153000\nDTEND;TZID=Europe/Berlin:20261017T173000\n"
                        + "RRULE:FREQ=WEEKLY;COUNT=3\nEND:VEVENT\nEND:VCALENDAR\n");

        IcsOccurrences.Result result = IcsOccurrences.expand(calendar, BERLIN,
                Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-11-15T00:00:00Z"), DEFAULT_DURATION);

        List<Instant> starts = result.occurrences().stream().map(IcsOccurrence::startsAt).sorted().toList();
        assertThat(starts).containsExactly(Instant.parse("2026-10-17T13:30:00Z"),
                Instant.parse("2026-10-24T13:30:00Z"), Instant.parse("2026-10-31T14:30:00Z"));
    }

    @Test
    void resolvesOutlookZones() {
        IcsCalendar calendar = IcsParser.parse(fixture("outlook.ics"));
        IcsOccurrences.Result result = IcsOccurrences.expand(calendar, BERLIN,
                Instant.parse("2026-09-19T00:00:00Z"), Instant.parse("2026-09-20T00:00:00Z"), DEFAULT_DURATION);

        assertThat(result.occurrences().stream().anyMatch(o -> "Handball: Finale".equals(o.summary())
                && o.startsAt().equals(Instant.parse("2026-09-19T16:00:00Z")))).isTrue();
        assertThat(result.occurrences().stream().anyMatch(o -> "Formula 1 Qualifying".equals(o.summary())
                && o.startsAt().equals(Instant.parse("2026-09-19T14:00:00Z")))).isTrue();
        assertThat(result.occurrences().stream().anyMatch(o -> "Unknown zone match".equals(o.summary())
                && o.startsAt().equals(Instant.parse("2026-09-19T10:00:00Z")))).isTrue();
        assertThat(result.unknownZones()).isEqualTo(1);
    }

    @Test
    void intervalAndUntil() {
        IcsCalendar calendar = IcsParser.parse(
                "BEGIN:VCALENDAR\nVERSION:2.0\nBEGIN:VEVENT\nUID:x\nDTSTART:20260901T100000Z\nDTEND:20260901T110000Z\n"
                        + "RRULE:FREQ=DAILY;INTERVAL=3;UNTIL=20260910T100000Z\nEND:VEVENT\nEND:VCALENDAR\n");

        IcsOccurrences.Result result = IcsOccurrences.expand(calendar, BERLIN,
                Instant.parse("2026-08-01T00:00:00Z"), Instant.parse("2026-10-01T00:00:00Z"), DEFAULT_DURATION);

        List<Instant> starts = result.occurrences().stream().map(IcsOccurrence::startsAt).sorted().toList();
        assertThat(starts).containsExactly(Instant.parse("2026-09-01T10:00:00Z"), Instant.parse("2026-09-04T10:00:00Z"),
                Instant.parse("2026-09-07T10:00:00Z"), Instant.parse("2026-09-10T10:00:00Z"));
    }

    @Test
    void dateExdateOnAllDaySeries() {
        IcsCalendar calendar = IcsParser.parse(
                "BEGIN:VCALENDAR\nVERSION:2.0\nX-WR-TIMEZONE:Europe/Berlin\nBEGIN:VEVENT\nUID:x\n"
                        + "DTSTART;VALUE=DATE:20260917\nRRULE:FREQ=DAILY;COUNT=3\nEXDATE;VALUE=DATE:20260918\nEND:VEVENT\nEND:VCALENDAR\n");

        IcsOccurrences.Result result = IcsOccurrences.expand(calendar, BERLIN,
                Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-10-01T00:00:00Z"), DEFAULT_DURATION);

        assertThat(result.occurrences()).extracting(IcsOccurrence::allDayDate)
                .containsExactlyInAnyOrder(java.time.LocalDate.of(2026, 9, 17), java.time.LocalDate.of(2026, 9, 19));
    }

    @Test
    void runawayRulesStop() {
        IcsCalendar calendar = IcsParser.parse(
                "BEGIN:VCALENDAR\nVERSION:2.0\nBEGIN:VEVENT\nUID:x\nDTSTART:18000101T100000Z\nDTEND:18000101T110000Z\n"
                        + "RRULE:FREQ=DAILY\nEND:VEVENT\nEND:VCALENDAR\n");

        IcsOccurrences.Result result = IcsOccurrences.expand(calendar, BERLIN,
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-12-31T00:00:00Z"), DEFAULT_DURATION);

        assertThat(result.occurrences()).isEmpty();
    }

    @Test
    void zeroLengthUsesTheDefault() {
        IcsCalendar calendar = IcsParser.parse(
                "BEGIN:VCALENDAR\nVERSION:2.0\nBEGIN:VEVENT\nUID:x\nDTSTART:20260919T100000Z\nDTEND:20260919T100000Z\nEND:VEVENT\nEND:VCALENDAR\n");

        IcsOccurrences.Result result = IcsOccurrences.expand(calendar, BERLIN,
                Instant.parse("2026-09-19T00:00:00Z"), Instant.parse("2026-09-20T00:00:00Z"), DEFAULT_DURATION);

        assertThat(result.occurrences()).hasSize(1);
        IcsOccurrence occurrence = result.occurrences().getFirst();
        assertThat(Duration.between(occurrence.startsAt(), occurrence.endsAt())).isEqualTo(DEFAULT_DURATION);
    }
}

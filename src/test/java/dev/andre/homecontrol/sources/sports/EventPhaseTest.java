package dev.andre.homecontrol.sources.sports;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class EventPhaseTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final Instant NOW = Instant.parse("2026-09-19T14:00:00Z");

    private static SportsEvent timed(String start, String end, SportsEvent.Status status) {
        return new SportsEvent("id", "calendar:c", "Title", Instant.parse(start), Instant.parse(end), null, null, status);
    }

    private static SportsEvent allDay(String start, String end) {
        return new SportsEvent("id", "calendar:c", "Title", Instant.parse(start), Instant.parse(end),
                LocalDate.of(2026, 9, 19), null, SportsEvent.Status.SCHEDULED);
    }

    @ParameterizedTest
    @CsvSource({
            "2026-09-19T13:30:00Z,2026-09-19T15:25:00Z,SCHEDULED,LIVE",
            "2026-09-19T14:00:00Z,2026-09-19T16:00:00Z,SCHEDULED,LIVE",
            "2026-09-19T16:30:00Z,2026-09-19T18:25:00Z,SCHEDULED,UPCOMING_TODAY",
            "2026-09-19T21:59:00Z,2026-09-19T23:59:00Z,SCHEDULED,UPCOMING_TODAY",
            "2026-09-19T22:00:00Z,2026-09-19T23:00:00Z,SCHEDULED,LATER",
            "2026-09-19T11:30:00Z,2026-09-19T13:30:00Z,SCHEDULED,ENDED",
            "2026-09-19T12:00:00Z,2026-09-19T13:59:59Z,SCHEDULED,ENDED",
            "2026-09-19T13:59:59Z,2026-09-19T14:00:00Z,SCHEDULED,ENDED",
            "2026-09-19T15:00:00Z,2026-09-19T17:00:00Z,LIVE,LIVE",
            "2026-09-19T13:30:00Z,2026-09-19T15:25:00Z,FINISHED,ENDED",
    })
    void timedEvents(String start, String end, SportsEvent.Status status, EventPhase expected) {
        assertThat(EventPhase.of(timed(start, end, status), NOW, BERLIN)).isEqualTo(expected);
    }

    @Test
    void allDayEvents() {
        assertThat(EventPhase.of(allDay("2026-09-18T22:00:00Z", "2026-09-19T22:00:00Z"), NOW, BERLIN))
                .isEqualTo(EventPhase.ALL_DAY_TODAY);
        assertThat(EventPhase.of(allDay("2026-09-19T22:00:00Z", "2026-09-20T22:00:00Z"), NOW, BERLIN))
                .isEqualTo(EventPhase.LATER);
        assertThat(EventPhase.of(allDay("2026-09-17T22:00:00Z", "2026-09-18T22:00:00Z"), NOW, BERLIN))
                .isEqualTo(EventPhase.ENDED);
        assertThat(EventPhase.of(allDay("2026-09-17T22:00:00Z", "2026-09-21T22:00:00Z"), NOW, BERLIN))
                .isEqualTo(EventPhase.ALL_DAY_TODAY);
    }
}

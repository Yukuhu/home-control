package dev.andre.homecontrol.sources.sports;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LiveTodayRailTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final Instant NOW = Instant.parse("2026-09-19T14:00:00Z");

    private static SportsEvent timed(String id, String title, String start, String end, SportsEvent.Status status) {
        return new SportsEvent(id, "calendar:c", title, Instant.parse(start), Instant.parse(end), null, null, status);
    }

    private static SportsEvent allDay(String id, String title, LocalDate date, String start, String end) {
        return new SportsEvent(id, "calendar:c", title, Instant.parse(start), Instant.parse(end), date, null,
                SportsEvent.Status.SCHEDULED);
    }

    @Test
    void ordersLiveThenAllDayThenUpcoming() {
        SportsEvent b = timed("id-b", "B", "2026-09-19T18:30:00Z", "2026-09-19T20:30:00Z", SportsEvent.Status.SCHEDULED);
        SportsEvent z = timed("id-z", "Z", "2026-09-19T13:30:00Z", "2026-09-19T15:30:00Z", SportsEvent.Status.SCHEDULED);
        SportsEvent vuelta = allDay("id-vuelta", "Vuelta", LocalDate.of(2026, 9, 19),
                "2026-09-18T22:00:00Z", "2026-09-19T22:00:00Z");
        SportsEvent a = timed("id-a", "A", "2026-09-19T13:30:00Z", "2026-09-19T15:30:00Z", SportsEvent.Status.SCHEDULED);
        SportsEvent c = timed("id-c", "C", "2026-09-19T16:30:00Z", "2026-09-19T18:30:00Z", SportsEvent.Status.SCHEDULED);
        SportsEvent early = timed("id-early", "Early", "2026-09-19T12:30:00Z", "2026-09-19T14:30:00Z", SportsEvent.Status.SCHEDULED);
        SportsEvent old = timed("id-old", "Old", "2026-09-19T11:00:00Z", "2026-09-19T13:00:00Z", SportsEvent.Status.SCHEDULED);
        SportsEvent tomorrow = timed("id-tomorrow", "Tomorrow", "2026-09-20T15:30:00Z", "2026-09-20T17:30:00Z", SportsEvent.Status.SCHEDULED);
        SportsEvent done = timed("id-done", "Done", "2026-09-19T13:30:00Z", "2026-09-19T15:30:00Z", SportsEvent.Status.FINISHED);
        SportsEvent allDayTomorrow = allDay("id-allday-tomorrow", "AllDayTomorrow", LocalDate.of(2026, 9, 20),
                "2026-09-19T22:00:00Z", "2026-09-20T22:00:00Z");

        List<SportsEvent> input = new ArrayList<>(List.of(b, z, vuelta, a, c, early, old, tomorrow, done, allDayTomorrow));

        List<SportsEvent> selected = LiveTodayRail.select(input, NOW, BERLIN, 30);

        assertThat(selected).extracting(SportsEvent::title).containsExactly("Early", "A", "Z", "Vuelta", "C", "B");
    }

    @Test
    void titleTiesAreCaseInsensitiveThenById() {
        SportsEvent alphaLower = timed("tsdb:2", "alpha", "2026-09-19T13:30:00Z", "2026-09-19T15:30:00Z", SportsEvent.Status.SCHEDULED);
        SportsEvent alphaUpper = timed("tsdb:1", "Alpha", "2026-09-19T13:30:00Z", "2026-09-19T15:30:00Z", SportsEvent.Status.SCHEDULED);
        SportsEvent beta = timed("tsdb:3", "beta", "2026-09-19T13:30:00Z", "2026-09-19T15:30:00Z", SportsEvent.Status.SCHEDULED);

        List<SportsEvent> selected = LiveTodayRail.select(List.of(alphaLower, alphaUpper, beta), NOW, BERLIN, 30);

        assertThat(selected).extracting(SportsEvent::itemId).containsExactly("tsdb:1", "tsdb:2", "tsdb:3");
    }

    @Test
    void respectsTheLimitAndDropsDuplicateIds() {
        List<SportsEvent> fortyUpcoming = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            fortyUpcoming.add(timed("up-" + i, "Upcoming " + i, "2026-09-19T15:%02d:00Z".formatted(i),
                    "2026-09-19T17:%02d:00Z".formatted(i), SportsEvent.Status.SCHEDULED));
        }
        List<SportsEvent> selected = LiveTodayRail.select(fortyUpcoming, NOW, BERLIN, 30);
        assertThat(selected).hasSize(30);

        SportsEvent one = timed("dup", "Dup", "2026-09-19T15:00:00Z", "2026-09-19T17:00:00Z", SportsEvent.Status.SCHEDULED);
        SportsEvent duplicate = timed("dup", "Dup", "2026-09-19T15:00:00Z", "2026-09-19T17:00:00Z", SportsEvent.Status.SCHEDULED);
        assertThat(LiveTodayRail.select(List.of(one, duplicate), NOW, BERLIN, 30)).hasSize(1);
    }

    @Test
    void todayEndsAtLocalMidnight() {
        SportsEvent included = timed("id-1", "A", "2026-09-19T21:59:00Z", "2026-09-19T23:00:00Z", SportsEvent.Status.SCHEDULED);
        SportsEvent excluded = timed("id-2", "B", "2026-09-19T22:00:00Z", "2026-09-19T23:00:00Z", SportsEvent.Status.SCHEDULED);

        List<SportsEvent> selected = LiveTodayRail.select(List.of(included, excluded), NOW, BERLIN, 30);
        assertThat(selected).extracting(SportsEvent::itemId).containsExactly("id-1");

        List<SportsEvent> selectedNewYork = LiveTodayRail.select(List.of(included, excluded), NOW, ZoneId.of("America/New_York"), 30);
        assertThat(selectedNewYork).extracting(SportsEvent::itemId).contains("id-2");
    }
}

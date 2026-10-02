package dev.andre.homecontrol.sources.sports.calendar;

import dev.andre.homecontrol.sources.sports.SportsItems;
import dev.andre.homecontrol.sources.sports.feed.SportsEvent;
import dev.andre.homecontrol.sources.sports.ics.IcsCalendar;
import dev.andre.homecontrol.sources.sports.ics.IcsOccurrence;
import dev.andre.homecontrol.sources.sports.ics.IcsOccurrences;
import dev.andre.homecontrol.sources.sports.ics.IcsParser;
import dev.andre.homecontrol.sources.sports.settings.SportsSettings;
import dev.andre.homecontrol.testsupport.Fixtures;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the sports source makes of the calendar recordings. The recordings themselves are checked in the protocols
 * module, by IcsFixtureContractTest.
 */
class CalendarFixtureContractTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final List<String> VALID_FIXTURES = List.of("bundesliga.ics", "recurring.ics", "outlook.ics");
    private static final Pattern MAPPED_ID = Pattern.compile("^ics:c-3f9a1c2b7d4e:[0-9a-f]{16}$");

    @Test
    void mappedEventsAreWellFormed() throws IOException {
        SportsSettings settings = SportsSettings.empty().withCalendars(List.of(
                new SportsSettings.CalendarEntry("c-3f9a1c2b7d4e", "Bundesliga 2026/27", "calendar.example.org", null, Instant.EPOCH)));
        for (String name : VALID_FIXTURES) {
            IcsCalendar calendar = IcsParser.parse(Fixtures.read("ics/" + name));
            IcsOccurrences.Result result = IcsOccurrences.expand(calendar, BERLIN,
                    Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-10-10T00:00:00Z"), Duration.ofMinutes(120));
            Set<String> ids = new HashSet<>();
            for (IcsOccurrence occurrence : result.occurrences()) {
                SportsEvent event = CalendarSchedule.toEvent("c-3f9a1c2b7d4e", occurrence);
                assertThat(event.itemId()).as(name).matches(MAPPED_ID.pattern());
                assertThat(ids.add(event.itemId())).as(name + ": unique id " + event.itemId()).isTrue();
                assertThat(event.competitionKey()).isEqualTo("calendar:c-3f9a1c2b7d4e");

                var item = SportsItems.toItem(event, settings, BERLIN, Locale.forLanguageTag("de-DE"), Instant.parse("2026-09-19T14:00:00Z"));
                assertThat(item.kind().name()).isEqualTo("LIVE_EVENT");
                assertThat(item.sourceId()).isEqualTo("sports");
                assertThat(item.subtitle()).as(name).isNotBlank();
                assertThat(item.startsAt()).isEqualTo(event.startsAt());
                assertThat(item.endsAt()).isEqualTo(event.endsAt());
            }
        }
    }

    @Test
    void idsAreStableAcrossParses() throws IOException {
        List<String> first = mappedIds();
        List<String> second = mappedIds();
        assertThat(first).isEqualTo(second);
    }

    private static List<String> mappedIds() throws IOException {
        IcsCalendar calendar = IcsParser.parse(Fixtures.read("ics/bundesliga.ics"));
        IcsOccurrences.Result result = IcsOccurrences.expand(calendar, BERLIN,
                Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-10-10T00:00:00Z"), Duration.ofMinutes(120));
        return result.occurrences().stream()
                .map(occurrence -> CalendarSchedule.toEvent("c-3f9a1c2b7d4e", occurrence).itemId())
                .toList();
    }
}

package dev.andre.homecontrol.sources.sports.ics;

import dev.andre.homecontrol.sources.sports.SportsEvent;
import dev.andre.homecontrol.sources.sports.SportsItems;
import dev.andre.homecontrol.sources.sports.SportsSettings;
import dev.andre.homecontrol.sources.sports.calendar.CalendarSchedule;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class IcsFixtureContractTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final List<String> VALID_FIXTURES = List.of("bundesliga.ics", "recurring.ics", "outlook.ics");
    private static final Pattern MAPPED_ID = Pattern.compile("^ics:c-3f9a1c2b7d4e:[0-9a-f]{16}$");

    private static String fixture(String name) {
        try (InputStream in = IcsFixtureContractTest.class.getResourceAsStream("/fixtures/ics/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void everyCalendarFixtureIsReadable() throws Exception {
        for (String name : VALID_FIXTURES) {
            IcsCalendar calendar = IcsParser.parse(fixture(name));
            assertThat(calendar.skippedEvents()).isZero();
            assertThat(calendar.events()).hasSizeGreaterThanOrEqualTo(3);
        }
        IcsCalendar broken = IcsParser.parse(fixture("broken.ics"));
        assertThat(broken.skippedEvents()).isGreaterThan(0);

        String invalidFixture = fixture("not-a-calendar.html");
        org.junit.jupiter.api.Assertions.assertThrows(IcsFormatException.class, () -> IcsParser.parse(invalidFixture));

        Path dir = Path.of(IcsFixtureContractTest.class.getResource("/fixtures/ics").toURI());
        try (Stream<Path> listing = Files.list(dir)) {
            List<String> names = listing.map(p -> p.getFileName().toString()).sorted().toList();
            assertThat(names).containsExactlyInAnyOrder(
                    "broken.ics", "bundesliga.ics", "not-a-calendar.html", "outlook.ics", "recurring.ics");
        }
    }

    @Test
    void validFixturesFollowTheRfcShape() {
        for (String name : VALID_FIXTURES) {
            String raw = fixture(name);
            IcsCalendar calendar = IcsParser.parse(raw);
            for (IcsEvent event : calendar.events()) {
                assertThat(event.start()).as(name + ": DTSTART").isNotNull();
            }
            for (String line : IcsParser.unfold(raw)) {
                if (line.isBlank()) {
                    continue;
                }
                assertThat(line).as(name + ": every unfolded line has a colon").contains(":");
            }
            Deque<String> stack = new ArrayDeque<>();
            for (String line : IcsParser.unfold(raw)) {
                String upper = line.strip().toUpperCase(Locale.ROOT);
                if (upper.startsWith("BEGIN:")) {
                    stack.push(upper.substring("BEGIN:".length()));
                } else if (upper.startsWith("END:")) {
                    String component = upper.substring("END:".length());
                    assertThat(stack.isEmpty() ? null : stack.pop()).as(name + ": matching BEGIN/END").isEqualTo(component);
                }
            }
            assertThat(stack).as(name + ": every BEGIN closed").isEmpty();
        }
        assertThat(fixture("bundesliga.ics").lines().anyMatch(l -> l.startsWith(" ") || l.startsWith("\t")))
                .as("bundesliga.ics has a folded continuation line").isTrue();
    }

    @Test
    void occurrencesAreWellFormed() {
        for (String name : VALID_FIXTURES) {
            IcsCalendar calendar = IcsParser.parse(fixture(name));
            IcsOccurrences.Result result = IcsOccurrences.expand(calendar, BERLIN,
                    Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-10-10T00:00:00Z"), Duration.ofMinutes(120));
            for (IcsOccurrence occurrence : result.occurrences()) {
                assertThat(occurrence.summary()).as(name).isNotNull();
                assertThat(occurrence.endsAt()).as(name).isAfter(occurrence.startsAt());
                if (occurrence.allDayDate() != null) {
                    assertThat(occurrence.startsAt()).isEqualTo(occurrence.allDayDate().atStartOfDay(BERLIN).toInstant());
                }
            }
        }
    }

    @Test
    void mappedEventsAreWellFormed() {
        SportsSettings settings = SportsSettings.empty().withCalendars(List.of(
                new SportsSettings.CalendarEntry("c-3f9a1c2b7d4e", "Bundesliga 2026/27", "calendar.example.org", null, Instant.EPOCH)));
        for (String name : VALID_FIXTURES) {
            IcsCalendar calendar = IcsParser.parse(fixture(name));
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
    void idsAreStableAcrossParses() {
        List<String> first = mappedIds();
        List<String> second = mappedIds();
        assertThat(first).isEqualTo(second);
    }

    private static List<String> mappedIds() {
        IcsCalendar calendar = IcsParser.parse(fixture("bundesliga.ics"));
        IcsOccurrences.Result result = IcsOccurrences.expand(calendar, BERLIN,
                Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-10-10T00:00:00Z"), Duration.ofMinutes(120));
        return result.occurrences().stream()
                .map(occurrence -> CalendarSchedule.toEvent("c-3f9a1c2b7d4e", occurrence).itemId())
                .toList();
    }
}

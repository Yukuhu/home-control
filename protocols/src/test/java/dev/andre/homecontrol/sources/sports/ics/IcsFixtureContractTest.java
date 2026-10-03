package dev.andre.homecontrol.sources.sports.ics;

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
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class IcsFixtureContractTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final List<String> VALID_FIXTURES = List.of("bundesliga.ics", "recurring.ics", "outlook.ics",
            "bundesliga-folded.ics");

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

        // The recordings sit in this module's test fixtures, and the module's tests run in its directory.
        Path dir = Path.of("src/testFixtures/resources/fixtures/ics");
        try (Stream<Path> listing = Files.list(dir)) {
            List<String> names = listing.map(p -> p.getFileName().toString()).sorted().toList();
            assertThat(names).containsExactlyInAnyOrder("broken.ics", "bundesliga.ics", "bundesliga-folded.ics",
                    "not-a-calendar.html", "outlook.ics", "recurring.ics");
        }
    }

    @Test
    void theFoldedFixtureIsAsRoughAsARealFeed() throws IOException {
        byte[] raw;
        try (InputStream in = IcsFixtureContractTest.class.getResourceAsStream("/fixtures/ics/bundesliga-folded.ics")) {
            raw = in.readAllBytes();
        }
        String text = new String(raw, StandardCharsets.ISO_8859_1);
        assertThat(text).as("CRLF line ends, and a fold between the two bytes of a character")
                .contains("\r\n").doesNotContainPattern("[^\r]\n").contains("\u00c3\r\n \u009c");
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
}

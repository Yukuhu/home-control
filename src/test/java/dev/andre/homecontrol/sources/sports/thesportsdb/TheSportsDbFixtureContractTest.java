package dev.andre.homecontrol.sources.sports.thesportsdb;

import dev.andre.homecontrol.sources.sports.SportsProperties;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class TheSportsDbFixtureContractTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final Pattern TIMESTAMP = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(Z|[+-]\\d{2}:\\d{2})?$");
    private static final Pattern EVENTSDAY_NAME = Pattern.compile("^eventsday-(\\d{4}-\\d{2}-\\d{2})-(\\d+)\\.json$");

    private static Path dir() throws Exception {
        return Path.of(TheSportsDbFixtureContractTest.class.getResource("/fixtures/thesportsdb").toURI());
    }

    private static List<Path> allFixtures() throws Exception {
        try (Stream<Path> listing = Files.list(dir())) {
            return listing.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList();
        }
    }

    private static JsonNode read(Path path) throws IOException {
        return MAPPER.readTree(Files.readAllBytes(path));
    }

    @Test
    void everyFixtureIsAJsonObject() throws Exception {
        List<Path> fixtures = allFixtures();
        assertThat(fixtures).hasSize(9);
        for (Path path : fixtures) {
            assertThat(read(path).isObject()).as(path.getFileName().toString()).isTrue();
        }
    }

    @Test
    void leagueFixturesHaveTheDocumentedShape() throws Exception {
        for (Path path : allFixtures()) {
            String name = path.getFileName().toString();
            if (!name.startsWith("lookupleague-")) {
                continue;
            }
            JsonNode leagues = read(path).path("leagues");
            if (leagues.isArray()) {
                for (JsonNode league : leagues) {
                    assertThat(league.path("idLeague").asString("")).as(name).matches("^[0-9]+$");
                    assertThat(league.path("strLeague").asString("")).as(name).isNotBlank();
                    assertThat(league.path("strSport").asString("")).as(name).isNotBlank();
                    assertThat(league.path("strCountry").asString("")).as(name).isNotBlank();
                    JsonNode badge = league.path("strBadge");
                    assertThat(badge.isNull() || badge.asString("").startsWith("https://")).as(name).isTrue();
                }
            }
        }

        JsonNode search = read(dir().resolve("search_all_leagues-germany-soccer.json"));
        JsonNode countries = search.path("countries");
        assertThat(countries.isArray()).isTrue();
        for (JsonNode entry : countries) {
            assertThat(entry.path("idLeague").isMissingNode()).isFalse();
            assertThat(entry.path("strLeague").isMissingNode()).isFalse();
        }
    }

    @Test
    void eventFixturesHaveTheDocumentedShape() throws Exception {
        for (Path path : allFixtures()) {
            String name = path.getFileName().toString();
            if (!name.startsWith("eventsday-")) {
                continue;
            }
            JsonNode events = read(path).path("events");
            assertThat(events.isArray() || events.isNull()).as(name).isTrue();
            if (!events.isArray()) {
                continue;
            }
            for (JsonNode event : events) {
                assertThat(event.path("idEvent").isMissingNode()).as(name).isFalse();
                assertThat(event.path("idLeague").isMissingNode()).as(name).isFalse();
                assertThat(event.path("strStatus").isMissingNode()).as(name).isFalse();
                boolean hasTimestamp = event.path("strTimestamp").isString() && !event.path("strTimestamp").asString().isBlank();
                boolean hasDate = event.path("dateEvent").isString() && !event.path("dateEvent").asString().isBlank();
                assertThat(hasTimestamp || hasDate).as(name + ": " + event).isTrue();

                if (hasTimestamp) {
                    Matcher m = TIMESTAMP.matcher(event.path("strTimestamp").asString());
                    assertThat(m.matches()).as(name + ": strTimestamp shape").isTrue();
                }
                for (String field : List.of("strThumb", "strPoster")) {
                    JsonNode value = event.path(field);
                    if (value.isString()) {
                        String raw = value.asString();
                        assertThat(raw.isBlank() || raw.startsWith("https://r2.thesportsdb.com/")).as(name + ": " + field).isTrue();
                    }
                }
            }
        }
    }

    @Test
    void fixtureFileNamesMatchTheirContent() throws Exception {
        for (Path path : allFixtures()) {
            String name = path.getFileName().toString();
            Matcher m = EVENTSDAY_NAME.matcher(name);
            if (!m.matches()) {
                continue;
            }
            String date = m.group(1);
            String league = m.group(2);
            JsonNode events = read(path).path("events");
            if (!events.isArray()) {
                continue;
            }
            for (JsonNode event : events) {
                String idEvent = event.path("idEvent").asString("");
                String idLeague = event.path("idLeague").asString("");
                if (idEvent.matches("^[0-9]+$") && idLeague.equals(league)) {
                    assertThat(event.path("dateEvent").asString("")).as(name + ": " + idEvent).isEqualTo(date);
                }
            }
        }
    }

    @Test
    void mappedEventsAreWellFormed() throws Exception {
        java.util.function.Function<String, Duration> durations = sport -> SportsProperties.TheSportsDb.DEFAULT_DURATIONS
                .getOrDefault(sport == null ? "" : sport.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", ""), Duration.ofMinutes(120));

        for (Path path : allFixtures()) {
            String name = path.getFileName().toString();
            Matcher m = EVENTSDAY_NAME.matcher(name);
            if (!m.matches()) {
                continue;
            }
            String league = m.group(2);
            JsonNode events = read(path).path("events");
            if (!events.isArray()) {
                continue;
            }
            for (JsonNode event : events) {
                TheSportsDbEventMapper.toEvent(event, league, null, BERLIN, durations).ifPresent(mapped -> {
                    assertThat(mapped.itemId()).as(name).matches("^tsdb:[0-9]+$");
                    assertThat(mapped.competitionKey()).isEqualTo("thesportsdb:" + league);
                    assertThat(mapped.title()).as(name).isNotBlank();
                    assertThat(mapped.title().length()).isLessThanOrEqualTo(200);
                    assertThat(mapped.endsAt()).isAfter(mapped.startsAt());
                    if (mapped.artwork() != null) {
                        assertThat(mapped.artwork().toString()).startsWith("https://");
                    }
                });
            }
        }
    }

    @Test
    void noFixtureContainsAPersonalKey() throws Exception {
        for (Path path : allFixtures()) {
            String text = Files.readString(path, StandardCharsets.UTF_8);
            assertThat(text).as(path.getFileName().toString()).doesNotContain(FakeTheSportsDbServer.PERSONAL_KEY);
        }
    }
}

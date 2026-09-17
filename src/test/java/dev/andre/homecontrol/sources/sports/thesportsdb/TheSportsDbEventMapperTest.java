package dev.andre.homecontrol.sources.sports.thesportsdb;

import dev.andre.homecontrol.sources.sports.SportsEvent;
import dev.andre.homecontrol.sources.sports.SportsProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class TheSportsDbEventMapperTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final URI BADGE = URI.create("https://r2.thesportsdb.com/images/media/league/badge/teqh1b1679952008.png");
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static final Function<String, Duration> DEFAULT_DURATIONS = sport ->
            SportsProperties.TheSportsDb.DEFAULT_DURATIONS.getOrDefault(
                    sport == null ? "" : sport.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", ""),
                    Duration.ofMinutes(120));

    private static JsonNode fixture(String name) {
        try (InputStream in = TheSportsDbEventMapperTest.class.getResourceAsStream("/fixtures/thesportsdb/" + name)) {
            return MAPPER.readTree(in.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<JsonNode> events(String fixtureName) {
        List<JsonNode> out = new java.util.ArrayList<>();
        fixture(fixtureName).path("events").forEach(out::add);
        return out;
    }

    private static Optional<SportsEvent> map(JsonNode node, String leagueId) {
        return TheSportsDbEventMapper.toEvent(node, leagueId, BADGE, BERLIN, DEFAULT_DURATIONS);
    }

    @Test
    void mapsTheNineteenthOfSeptember() {
        List<JsonNode> events = events("eventsday-2026-09-19-4331.json");

        SportsEvent werder = map(events.get(0), "4331").orElseThrow();
        assertThat(werder.itemId()).isEqualTo("tsdb:2508361");
        assertThat(werder.title()).isEqualTo("Werder Bremen vs Augsburg");
        assertThat(werder.startsAt()).isEqualTo(Instant.parse("2026-09-19T13:30:00Z"));
        assertThat(werder.endsAt()).isEqualTo(Instant.parse("2026-09-19T15:30:00Z"));
        assertThat(werder.status()).isEqualTo(SportsEvent.Status.SCHEDULED);
        assertThat(werder.artwork().toString()).endsWith("ppxv5f1688630656.jpg/small");
        assertThat(werder.competitionKey()).isEqualTo("thesportsdb:4331");
        assertThat(werder.allDay()).isFalse();

        SportsEvent wolfsburg = map(events.get(1), "4331").orElseThrow();
        assertThat(wolfsburg.status()).isEqualTo(SportsEvent.Status.LIVE);
        assertThat(wolfsburg.artwork()).isEqualTo(BADGE);

        SportsEvent dortmund = map(events.get(2), "4331").orElseThrow();
        assertThat(dortmund.artwork().toString()).endsWith("qwe7rt1754041701.jpg/small");

        SportsEvent heidenheim = map(events.get(3), "4331").orElseThrow();
        assertThat(heidenheim.title()).isEqualTo("Heidenheim vs Hamburger SV");
        assertThat(heidenheim.startsAt()).isEqualTo(Instant.parse("2026-09-19T18:30:00Z"));
        assertThat(heidenheim.artwork()).isEqualTo(BADGE);

        assertThat(map(events.get(4), "4331")).isEmpty();
        assertThat(map(events.get(5), "4331")).isEmpty();
    }

    @Test
    void mapsTheEighteenth() {
        List<JsonNode> events = events("eventsday-2026-09-18-4331.json");

        SportsEvent bayern = map(events.get(0), "4331").orElseThrow();
        assertThat(bayern.itemId()).isEqualTo("tsdb:2508360");
        assertThat(bayern.status()).isEqualTo(SportsEvent.Status.FINISHED);

        assertThat(map(events.get(1), "4331")).isEmpty();
        assertThat(map(events.get(2), "4331")).isEmpty();
    }

    @Test
    void offsetTimestampsAndDateOnlyEvents() {
        List<JsonNode> events = events("eventsday-2026-09-19-4328.json");

        SportsEvent arsenal = map(events.get(0), "4328").orElseThrow();
        assertThat(arsenal.itemId()).isEqualTo("tsdb:2601001");
        assertThat(arsenal.status()).isEqualTo(SportsEvent.Status.FINISHED);
        assertThat(arsenal.startsAt()).isEqualTo(Instant.parse("2026-09-19T11:30:00Z"));

        SportsEvent liverpool = map(events.get(1), "4328").orElseThrow();
        assertThat(liverpool.status()).isEqualTo(SportsEvent.Status.SCHEDULED);
        assertThat(liverpool.startsAt()).isEqualTo(Instant.parse("2026-09-19T14:00:00Z"));

        SportsEvent brighton = map(events.get(2), "4328").orElseThrow();
        assertThat(brighton.allDay()).isTrue();
        assertThat(brighton.startsAt()).isEqualTo(Instant.parse("2026-09-18T22:00:00Z"));
        assertThat(brighton.endsAt()).isEqualTo(Instant.parse("2026-09-19T22:00:00Z"));
    }

    @ParameterizedTest
    @CsvSource({
            "Match Finished, FINISHED",
            "AET, FINISHED",
            "HT, LIVE",
            "Q3, LIVE",
    })
    void statusSets(String strStatus, SportsEvent.Status expected) {
        JsonNode node = MAPPER.readTree(
                "{\"idEvent\":\"1\",\"idLeague\":\"4331\",\"strEvent\":\"A vs B\",\"strTimestamp\":\"2026-09-19T10:00:00Z\",\"strStatus\":\""
                        + strStatus + "\"}");
        assertThat(map(node, "4331").orElseThrow().status()).isEqualTo(expected);
    }

    @Test
    void statusSetsEmptyCases() {
        JsonNode susp = MAPPER.readTree(
                "{\"idEvent\":\"1\",\"idLeague\":\"4331\",\"strEvent\":\"A vs B\",\"strTimestamp\":\"2026-09-19T10:00:00Z\",\"strStatus\":\"Susp\"}");
        assertThat(map(susp, "4331")).isEmpty();

        JsonNode awarded = MAPPER.readTree(
                "{\"idEvent\":\"1\",\"idLeague\":\"4331\",\"strEvent\":\"A vs B\",\"strTimestamp\":\"2026-09-19T10:00:00Z\",\"strStatus\":\"Awarded\"}");
        assertThat(map(awarded, "4331")).isEmpty();

        JsonNode postponed = MAPPER.readTree(
                "{\"idEvent\":\"1\",\"idLeague\":\"4331\",\"strEvent\":\"A vs B\",\"strTimestamp\":\"2026-09-19T10:00:00Z\",\"strPostponed\":\"YES\"}");
        assertThat(map(postponed, "4331")).isEmpty();

        JsonNode scheduled = MAPPER.readTree(
                "{\"idEvent\":\"1\",\"idLeague\":\"4331\",\"strEvent\":\"A vs B\",\"strTimestamp\":\"2026-09-19T10:00:00Z\",\"strStatus\":\"\"}");
        assertThat(map(scheduled, "4331").orElseThrow().status()).isEqualTo(SportsEvent.Status.SCHEDULED);
    }

    @Test
    void durationsFollowTheSport() {
        JsonNode iceHockey = MAPPER.readTree(
                "{\"idEvent\":\"1\",\"idLeague\":\"4331\",\"strEvent\":\"A vs B\",\"strSport\":\"Ice Hockey\",\"strTimestamp\":\"2026-09-19T10:00:00Z\"}");
        SportsEvent event = map(iceHockey, "4331").orElseThrow();
        assertThat(java.time.Duration.between(event.startsAt(), event.endsAt())).isEqualTo(Duration.ofMinutes(165));

        JsonNode football = MAPPER.readTree(
                "{\"idEvent\":\"1\",\"idLeague\":\"4331\",\"strEvent\":\"A vs B\",\"strSport\":\"American Football\",\"strTimestamp\":\"2026-09-19T10:00:00Z\"}");
        SportsEvent event2 = map(football, "4331").orElseThrow();
        assertThat(Duration.between(event2.startsAt(), event2.endsAt())).isEqualTo(Duration.ofMinutes(210));

        JsonNode curling = MAPPER.readTree(
                "{\"idEvent\":\"1\",\"idLeague\":\"4331\",\"strEvent\":\"A vs B\",\"strSport\":\"Curling\",\"strTimestamp\":\"2026-09-19T10:00:00Z\"}");
        SportsEvent event3 = map(curling, "4331").orElseThrow();
        assertThat(Duration.between(event3.startsAt(), event3.endsAt())).isEqualTo(Duration.ofMinutes(120));
    }

    @Test
    void numericIdsAndTitleFallbacks() {
        JsonNode numeric = MAPPER.readTree(
                "{\"idEvent\":2508361,\"idLeague\":\"4331\",\"strEvent\":\"A vs B\",\"strTimestamp\":\"2026-09-19T10:00:00Z\"}");
        assertThat(map(numeric, "4331").orElseThrow().itemId()).isEqualTo("tsdb:2508361");

        JsonNode blankTitleNoAway = MAPPER.readTree(
                "{\"idEvent\":\"1\",\"idLeague\":\"4331\",\"strEvent\":\"\",\"strHomeTeam\":\"A\",\"strAwayTeam\":\"\",\"strTimestamp\":\"2026-09-19T10:00:00Z\"}");
        assertThat(map(blankTitleNoAway, "4331")).isEmpty();

        String longTitle = "a".repeat(250);
        JsonNode tooLong = MAPPER.readTree(
                "{\"idEvent\":\"1\",\"idLeague\":\"4331\",\"strEvent\":\"" + longTitle + "\",\"strTimestamp\":\"2026-09-19T10:00:00Z\"}");
        SportsEvent event = map(tooLong, "4331").orElseThrow();
        assertThat(event.title()).hasSize(200).endsWith("…");
    }

    @Test
    void artworkRules() {
        JsonNode httpThumb = MAPPER.readTree(
                "{\"idEvent\":\"1\",\"idLeague\":\"4331\",\"strEvent\":\"A vs B\",\"strTimestamp\":\"2026-09-19T10:00:00Z\",\"strThumb\":\"http://r2.thesportsdb.com/x.jpg\"}");
        assertThat(map(httpThumb, "4331").orElseThrow().artwork()).isEqualTo(BADGE);

        JsonNode cdnThumb = MAPPER.readTree(
                "{\"idEvent\":\"1\",\"idLeague\":\"4331\",\"strEvent\":\"A vs B\",\"strTimestamp\":\"2026-09-19T10:00:00Z\",\"strThumb\":\"https://cdn.example/x.jpg\"}");
        assertThat(map(cdnThumb, "4331").orElseThrow().artwork().toString()).isEqualTo("https://cdn.example/x.jpg");

        JsonNode wwwThumb = MAPPER.readTree(
                "{\"idEvent\":\"1\",\"idLeague\":\"4331\",\"strEvent\":\"A vs B\",\"strTimestamp\":\"2026-09-19T10:00:00Z\",\"strThumb\":\"https://www.thesportsdb.com/images/x.PNG\"}");
        assertThat(map(wwwThumb, "4331").orElseThrow().artwork().toString()).endsWith("x.PNG/small");

        JsonNode gifThumb = MAPPER.readTree(
                "{\"idEvent\":\"1\",\"idLeague\":\"4331\",\"strEvent\":\"A vs B\",\"strTimestamp\":\"2026-09-19T10:00:00Z\",\"strThumb\":\"https://r2.thesportsdb.com/images/x.gif\"}");
        assertThat(map(gifThumb, "4331").orElseThrow().artwork().toString()).isEqualTo("https://r2.thesportsdb.com/images/x.gif");
    }

    @Test
    void unparsableTimesFallBack() {
        JsonNode fallback = MAPPER.readTree(
                "{\"idEvent\":\"1\",\"idLeague\":\"4331\",\"strEvent\":\"A vs B\",\"strTimestamp\":\"soon\",\"dateEvent\":\"2026-09-19\",\"strTime\":\"15:00\"}");
        assertThat(map(fallback, "4331").orElseThrow().startsAt()).isEqualTo(Instant.parse("2026-09-19T15:00:00Z"));

        JsonNode nothing = MAPPER.readTree(
                "{\"idEvent\":\"1\",\"idLeague\":\"4331\",\"strEvent\":\"A vs B\",\"strTimestamp\":\"soon\",\"dateEvent\":\"nope\"}");
        assertThat(map(nothing, "4331")).isEmpty();
    }

    @Test
    void wrongLeagueIsEmpty() {
        JsonNode node = MAPPER.readTree(
                "{\"idEvent\":\"1\",\"idLeague\":\"4331\",\"strEvent\":\"A vs B\",\"strTimestamp\":\"2026-09-19T10:00:00Z\"}");
        assertThat(map(node, "9999")).isEmpty();
    }
}

package dev.andre.homecontrol.sources.sports.thesportsdb;

import dev.andre.homecontrol.sources.sports.SportsProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.io.IOException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TheSportsDbClientTest {

    private FakeTheSportsDbServer server;
    private TheSportsDbClient client;

    @BeforeEach
    void start() throws IOException {
        server = new FakeTheSportsDbServer().withStandardResponses();
        SportsProperties.TheSportsDb properties = new SportsProperties.TheSportsDb(
                true, server.apiBase(), "123", Duration.ofHours(24), 1, 2, null);
        client = new TheSportsDbClient(properties);
    }

    @AfterEach
    void stop() {
        server.close();
    }

    @Test
    void buildsTheDocumentedRequests() {
        client.lookupLeague("123", "4331");
        FakeTheSportsDbServer.Recorded lookup = server.last("lookupleague.php");
        assertThat(lookup.key()).isEqualTo("123");
        assertThat(lookup.query()).isEqualTo(Map.of("id", "4331"));
        assertThat(lookup.header("accept")).isEqualTo("application/json");

        client.searchLeagues("123", "Germany", "Soccer");
        List<String> order = List.copyOf(server.last("search_all_leagues.php").query().keySet());
        assertThat(order).containsExactly("c", "s");

        client.eventsDay("123", LocalDate.of(2026, 9, 19), "4331");
        assertThat(server.last("eventsday.php").query()).isEqualTo(Map.of("d", "2026-09-19", "l", "4331"));
    }

    @Test
    void mapsLeagues() {
        League bundesliga = client.lookupLeague("123", "4331").orElseThrow();
        assertThat(bundesliga.id()).isEqualTo("4331");
        assertThat(bundesliga.name()).isEqualTo("German Bundesliga");
        assertThat(bundesliga.sport()).isEqualTo("Soccer");
        assertThat(bundesliga.country()).isEqualTo("Germany");
        assertThat(bundesliga.badge().toString()).contains("teqh1b1679952008.png");

        assertThat(client.lookupLeague("123", "999")).isEmpty();
    }

    @Test
    void searchSkipsUnmappableLeagues() {
        List<League> leagues = client.searchLeagues("123", "Germany", "Soccer");
        assertThat(leagues).extracting(League::id).containsExactly("4485", "4399", "4331", "5891");
        assertThat(leagues.stream().filter(l -> l.id().equals("5891")).findFirst().orElseThrow().badge()).isNull();
    }

    @Test
    void eventsDayReturnsElementsOrNothing() {
        assertThat(client.eventsDay("123", LocalDate.of(2026, 9, 19), "4331")).hasSize(6);
        assertThat(client.eventsDay("123", LocalDate.of(2026, 9, 20), "4331")).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
            "429, RATE_LIMITED, TheSportsDB is limiting requests; try again in a minute",
            "502, SERVER_ERROR, TheSportsDB had a server error (HTTP 502)",
    })
    void mapsStatuses(int status, String kind, String message) {
        server.respondJson("lookupleague.php", Map.of("id", "s" + status), status, "{}");
        assertThatThrownBy(() -> client.lookupLeague("123", "s" + status))
                .isInstanceOf(TheSportsDbException.class)
                .hasFieldOrPropertyWithValue("kind", TheSportsDbException.Kind.valueOf(kind))
                .hasMessage(message);
    }

    @Test
    void mapsKeyRejectionAndBadResponses() {
        server.respondJson("lookupleague.php", Map.of("id", "bad400"), 400, readInvalidKey());
        assertThatThrownBy(() -> client.lookupLeague("123", "bad400"))
                .isInstanceOf(TheSportsDbException.class)
                .hasFieldOrPropertyWithValue("kind", TheSportsDbException.Kind.UNAUTHORIZED)
                .hasMessage("TheSportsDB rejected the API key");

        server.respondJson("lookupleague.php", Map.of("id", "e401"), 401, "{}");
        assertThatThrownBy(() -> client.lookupLeague("123", "e401"))
                .hasFieldOrPropertyWithValue("kind", TheSportsDbException.Kind.UNAUTHORIZED);

        server.respondJson("lookupleague.php", Map.of("id", "e404m"), 404, "{\"Message\":\"Invalid API key\"}");
        assertThatThrownBy(() -> client.lookupLeague("123", "e404m"))
                .hasFieldOrPropertyWithValue("kind", TheSportsDbException.Kind.UNAUTHORIZED);

        server.respondJson("lookupleague.php", Map.of("id", "e404"), 404, "{}");
        assertThatThrownBy(() -> client.lookupLeague("123", "e404"))
                .hasFieldOrPropertyWithValue("kind", TheSportsDbException.Kind.BAD_RESPONSE)
                .hasMessage("TheSportsDB answered HTTP 404");

        server.respondJson("lookupleague.php", Map.of("id", "arr"), 200, "[]");
        assertThatThrownBy(() -> client.lookupLeague("123", "arr"))
                .hasFieldOrPropertyWithValue("kind", TheSportsDbException.Kind.BAD_RESPONSE)
                .hasMessage("TheSportsDB answered with something unexpected");

        server.respondJson("lookupleague.php", Map.of("id", "html"), 200, "<html></html>");
        assertThatThrownBy(() -> client.lookupLeague("123", "html"))
                .hasMessage("TheSportsDB answered with something that is not JSON");
    }

    private static String readInvalidKey() {
        try (var in = FakeTheSportsDbServer.class.getResourceAsStream("/fixtures/thesportsdb/invalid-key.json")) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @Test
    void wrongKeysNeverLeaveTheServer() {
        assertThatThrownBy(() -> client.lookupLeague("12 3/..", "4331"))
                .isInstanceOf(TheSportsDbException.class)
                .hasFieldOrPropertyWithValue("kind", TheSportsDbException.Kind.UNAUTHORIZED)
                .hasMessage("That does not look like a TheSportsDB API key");
        assertThat(server.count("lookupleague.php")).isZero();
    }

    @Test
    void timeoutsAndSizeLimits() {
        server.delay(Duration.ofSeconds(3));
        assertThatThrownBy(() -> client.lookupLeague("123", "4331"))
                .isInstanceOf(TheSportsDbException.class)
                .hasFieldOrPropertyWithValue("kind", TheSportsDbException.Kind.UNREACHABLE)
                .hasMessage("Could not reach TheSportsDB");
        server.delay(Duration.ZERO);

        String huge = "{\"leagues\":\"" + "x".repeat(2 * 1024 * 1024 + 10) + "\"}";
        server.respondJson("lookupleague.php", Map.of("id", "huge"), 200, huge);
        assertThatThrownBy(() -> client.lookupLeague("123", "huge"))
                .hasFieldOrPropertyWithValue("kind", TheSportsDbException.Kind.BAD_RESPONSE)
                .hasMessage("TheSportsDB answered with more data than expected");
    }

    @Test
    void neverLeaksTheKey() {
        server.delay(Duration.ofSeconds(3));
        try {
            client.lookupLeague(FakeTheSportsDbServer.PERSONAL_KEY, "4331");
            org.junit.jupiter.api.Assertions.fail("expected a TheSportsDbException");
        } catch (TheSportsDbException e) {
            assertThat(e.getMessage()).doesNotContain(FakeTheSportsDbServer.PERSONAL_KEY, "/api/v1/json");
            assertThat(e.toString()).doesNotContain(FakeTheSportsDbServer.PERSONAL_KEY, "/api/v1/json");
            assertThat(e.getCause()).isNull();
            for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
                assertThat(cause.getMessage()).doesNotContain(FakeTheSportsDbServer.PERSONAL_KEY, "/api/v1/json");
                assertThat(cause.toString()).doesNotContain(FakeTheSportsDbServer.PERSONAL_KEY, "/api/v1/json");
            }
        }
    }

    @Test
    void doesNotFollowRedirects() {
        server.respondJson("lookupleague.php", Map.of("id", "redirect"), 302, "{}");
        assertThatThrownBy(() -> client.lookupLeague("123", "redirect"))
                .hasFieldOrPropertyWithValue("kind", TheSportsDbException.Kind.BAD_RESPONSE)
                .hasMessage("TheSportsDB answered HTTP 302");
    }
}

package dev.andre.homecontrol.sources.tmdb;

import dev.andre.homecontrol.core.content.ContentSourceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TmdbClientTest {

    FakeTmdbServer fake;
    TmdbClient client;
    TmdbCredential bearer;
    TmdbCredential apiKey;

    @BeforeEach
    void setUp() throws IOException {
        fake = new FakeTmdbServer().withStandardResponses();
        TmdbProperties properties = new TmdbProperties(true, fake.apiBase(), null, Duration.ofSeconds(1),
                Duration.ofSeconds(1), 20, 40,
                Duration.ofHours(24), Duration.ofHours(24), null, true);
        client = new TmdbClient(properties);
        bearer = TmdbCredential.parse(FakeTmdbServer.READ_TOKEN);
        apiKey = TmdbCredential.parse(FakeTmdbServer.API_KEY);
    }

    @AfterEach
    void tearDown() {
        fake.close();
    }

    @Test
    void bearerTokensGoInTheAuthorizationHeader() {
        JsonNode response = client.get(bearer, "/authentication", Map.of());

        assertThat(response.path("success").asBoolean()).isTrue();
        FakeTmdbServer.Recorded recorded = fake.last("GET", "/3/authentication");
        assertThat(recorded.header("authorization")).isEqualTo("Bearer " + FakeTmdbServer.READ_TOKEN);
        assertThat(recorded.query()).doesNotContainKey("api_key");
    }

    @Test
    void apiKeysGoInTheQueryLast() {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("query", "matrix");
        query.put("language", "de-DE");

        client.get(apiKey, "/search/multi", query);

        FakeTmdbServer.Recorded recorded = fake.last("GET", "/3/search/multi");
        assertThat(recorded.rawQuery()).isEqualTo("query=matrix&language=de-DE&api_key=" + FakeTmdbServer.API_KEY);
        assertThat(recorded.header("authorization")).isNull();
    }

    @Test
    void queryValuesAreEncoded() {
        Map<String, String> query = Map.of("query", "Tom & Jerry/ü");

        client.get(apiKey, "/search/multi", query);

        FakeTmdbServer.Recorded recorded = fake.last("GET", "/3/search/multi");
        assertThat(recorded.query()).containsEntry("query", "Tom & Jerry/ü");
        assertThat(recorded.header("accept")).isEqualTo("application/json");
    }

    @ParameterizedTest
    @CsvSource({
            "401, UNAUTHORIZED, TMDB rejected the API key or read access token",
            "403, UNAUTHORIZED, TMDB rejected the API key or read access token",
            "404, NOT_FOUND, TMDB does not know this title",
            "410, NOT_FOUND, TMDB does not know this title",
            "429, RATE_LIMITED, 'TMDB is limiting requests; try again in a moment'",
            "500, SERVER_ERROR, TMDB had a server error (HTTP 500)",
            "503, SERVER_ERROR, TMDB had a server error (HTTP 503)",
            "418, BAD_RESPONSE, TMDB answered HTTP 418"
    })
    void statusesMapToKinds(int status, String kind, String message) {
        fake.respondJson("GET", "/3/status-test", status, "{}");

        assertThatThrownBy(() -> client.get(bearer, "/status-test", Map.of()))
                .isInstanceOf(TmdbException.class)
                .hasMessage(message)
                .extracting(e -> ((TmdbException) e).kind())
                .isEqualTo(ContentSourceException.Kind.valueOf(kind));
    }

    @Test
    void redirectsAreNotFollowed() {
        fake.redirect("/3/configuration", "http://127.0.0.1:1/elsewhere");

        assertThatThrownBy(() -> client.get(bearer, "/configuration", Map.of()))
                .isInstanceOf(TmdbException.class)
                .hasMessage("TMDB answered HTTP 302");
        assertThat(fake.count("GET", "/elsewhere")).isZero();
    }

    @Test
    void nonJsonAndArraysAreBadResponses() {
        fake.respondBytes("GET", "/3/html-test", 200, "text/html", "<html>".getBytes());
        assertThatThrownBy(() -> client.get(bearer, "/html-test", Map.of()))
                .isInstanceOf(TmdbException.class)
                .hasMessage("TMDB answered with something that is not JSON");

        fake.respondJson("GET", "/3/array-test", 200, "[]");
        assertThatThrownBy(() -> client.get(bearer, "/array-test", Map.of()))
                .isInstanceOf(TmdbException.class)
                .hasMessage("TMDB answered with something unexpected");
    }

    @Test
    void oversizedBodiesAreRejected() {
        String padding = " ".repeat(2 * 1024 * 1024 + 10);
        String json = "{\"padding\":\"" + padding + "\"}";
        fake.respondBytes("GET", "/3/big", 200, "application/json", json.getBytes());

        assertThatThrownBy(() -> client.get(bearer, "/big", Map.of()))
                .isInstanceOf(TmdbException.class)
                .hasFieldOrPropertyWithValue("kind", ContentSourceException.Kind.TOO_LARGE)
                .hasMessage("TMDB at 127.0.0.1 sent more than 2 MB");
    }

    @Test
    void unreachableAndSlowServersAreUnreachable() {
        TmdbProperties properties = new TmdbProperties(true, URI.create("http://127.0.0.1:9/3"), null,
                Duration.ofSeconds(1), Duration.ofSeconds(1), 20, 40,
                Duration.ofHours(24), Duration.ofHours(24), null, true);
        TmdbClient unreachableClient = new TmdbClient(properties);

        assertThatThrownBy(() -> unreachableClient.get(bearer, "/authentication", Map.of()))
                .isInstanceOf(TmdbException.class)
                .hasMessage("Could not reach TMDB at 127.0.0.1 (connection refused)");
    }

    @Test
    void aSlowServerTimesOutQuickly() {
        fake.delay(Duration.ofSeconds(3));

        assertThatThrownBy(() -> client.get(bearer, "/authentication", Map.of()))
                .isInstanceOf(TmdbException.class)
                .hasMessage("Could not reach TMDB at 127.0.0.1 (request timed out)")
                .extracting(e -> ((TmdbException) e).kind()).isEqualTo(ContentSourceException.Kind.UNREACHABLE);
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 404, 429, 500})
    void noMessageLeaksTheKeyOnStatusFailures(int status) {
        fake.respondJson("GET", "/3/leak-test", status, "{}");

        assertNoLeak(() -> client.get(apiKey, "/leak-test", Map.of()));
        assertNoLeak(() -> client.get(bearer, "/leak-test", Map.of()));
    }

    @Test
    void noMessageLeaksTheKeyOnARedirect() {
        fake.redirect("/3/leak-redirect", "http://127.0.0.1:1/elsewhere");

        assertNoLeak(() -> client.get(apiKey, "/leak-redirect", Map.of()));
        assertNoLeak(() -> client.get(bearer, "/leak-redirect", Map.of()));
    }

    @Test
    void noMessageLeaksTheKeyOnANonJsonBody() {
        fake.respondBytes("GET", "/3/leak-html", 200, "text/html", "<html>".getBytes());

        assertNoLeak(() -> client.get(apiKey, "/leak-html", Map.of()));
        assertNoLeak(() -> client.get(bearer, "/leak-html", Map.of()));
    }

    @Test
    void noMessageLeaksTheKeyOnAnOversizedBody() {
        String padding = " ".repeat(2 * 1024 * 1024 + 10);
        String json = "{\"padding\":\"" + padding + "\"}";
        fake.respondBytes("GET", "/3/leak-big", 200, "application/json", json.getBytes());

        assertNoLeak(() -> client.get(apiKey, "/leak-big", Map.of()));
        assertNoLeak(() -> client.get(bearer, "/leak-big", Map.of()));
    }

    @Test
    void noMessageLeaksTheKeyOnACompressedBody() {
        fake.respondGzipped("/3/leak-gzip");

        assertNoLeak(() -> client.get(apiKey, "/leak-gzip", Map.of()));
        assertNoLeak(() -> client.get(bearer, "/leak-gzip", Map.of()));
    }

    @Test
    void noMessageLeaksTheKeyForAHostGivenByName() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        TmdbClient byName = new TmdbClient(new TmdbProperties(true, URI.create("http://localhost:" + closedPort + "/3"),
                null, Duration.ofSeconds(1), Duration.ofSeconds(1), 20, 40, Duration.ofHours(24), Duration.ofHours(24),
                null, true));

        assertNoLeak(() -> byName.get(apiKey, "/authentication", Map.of()));
        assertNoLeak(() -> byName.get(bearer, "/authentication", Map.of()));
    }

    @Test
    void noMessageLeaksTheKeyWhenUnreachable() {
        fake.close();

        assertNoLeak(() -> client.get(apiKey, "/authentication", Map.of()));
        assertNoLeak(() -> client.get(bearer, "/authentication", Map.of()));
    }

    /** Neither credential form (API key in the query, bearer token in the header) ever reaches a user-facing message. */
    private static void assertNoLeak(ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(TmdbException.class)
                .satisfies(e -> {
                    TmdbException exception = (TmdbException) e;
                    assertThat(exception.getMessage())
                            .doesNotContain(FakeTmdbServer.API_KEY)
                            .doesNotContain(FakeTmdbServer.READ_TOKEN)
                            .doesNotContain("api_key");
                    assertThat(exception).hasNoCause();
                });
    }

    @Test
    void aTmdbExceptionIsAContentSourceException() {
        assertThat(new TmdbException(ContentSourceException.Kind.INVALID_INPUT, "x"))
                .isInstanceOf(dev.andre.homecontrol.core.content.ContentSourceException.class);
    }
    /** A public API: a DNS answer of this machine is refused unless the setting allows it. */
    @Test
    void refusesLoopbackByDefault() {
        TmdbProperties production = new TmdbProperties(true, fake.apiBase(), null, Duration.ofSeconds(1),
                Duration.ofSeconds(1), 20, 40, Duration.ofHours(24), Duration.ofHours(24), null, false);
        try (TmdbClient refusing = new TmdbClient(production)) {
            assertThatThrownBy(() -> refusing.get(bearer, "/authentication", Map.of()))
                    .isInstanceOf(TmdbException.class)
                    .hasFieldOrPropertyWithValue("kind", ContentSourceException.Kind.BLOCKED);
            assertNoLeak(() -> refusing.get(apiKey, "/authentication", Map.of()));
        }
    }
    /** A hostile or broken server cannot exhaust the stack with nesting: the shared mapper stops at 64 levels. */
    @Test
    void refusesDeeplyNestedJson() {
        fake.respondJson("GET", "/3/deep", 200, "{\"a\":".repeat(100) + "{}" + "}".repeat(100));

        assertThatThrownBy(() -> client.get(bearer, "/deep", Map.of()))
                .isInstanceOf(TmdbException.class)
                .hasMessage("TMDB answered with something that is not JSON");
    }
}

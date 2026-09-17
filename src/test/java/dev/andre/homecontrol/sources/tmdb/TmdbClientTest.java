package dev.andre.homecontrol.sources.tmdb;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
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
        TmdbProperties properties = new TmdbProperties(true, fake.apiBase(), null, 1, 1, 20, 40,
                Duration.ofHours(24), Duration.ofHours(24), null);
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
        assertThat(recorded.query().get("query")).isEqualTo("Tom & Jerry/ü");
        assertThat(recorded.header("accept")).isEqualTo("application/json");
    }

    @ParameterizedTest
    @CsvSource({
            "401, UNAUTHORIZED, TMDB rejected the API key or read access token",
            "403, UNAUTHORIZED, TMDB rejected the API key or read access token",
            "404, NOT_FOUND, TMDB does not know this title",
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
                .isEqualTo(TmdbException.Kind.valueOf(kind));
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
                .hasMessage("TMDB answered with more data than expected");
    }

    @Test
    void unreachableAndSlowServersAreUnreachable() {
        TmdbProperties properties = new TmdbProperties(true, URI.create("http://127.0.0.1:9/3"), null, 1, 1, 20, 40,
                Duration.ofHours(24), Duration.ofHours(24), null);
        TmdbClient unreachableClient = new TmdbClient(properties);

        assertThatThrownBy(() -> unreachableClient.get(bearer, "/authentication", Map.of()))
                .isInstanceOf(TmdbException.class)
                .hasMessage("Could not reach TMDB at 127.0.0.1");
    }

    @Test
    void aSlowServerTimesOutQuickly() {
        fake.delay(Duration.ofSeconds(3));
        long start = System.nanoTime();

        assertThatThrownBy(() -> client.get(bearer, "/authentication", Map.of()))
                .isInstanceOf(TmdbException.class);

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
    }

    @Test
    void noMessageLeaksTheKey() {
        fake.close();
        try {
            client.get(apiKey, "/authentication", Map.of());
        } catch (TmdbException e) {
            assertThat(e.getMessage()).doesNotContain(FakeTmdbServer.API_KEY).doesNotContain("api_key");
            assertThat(String.valueOf(e.getCause())).doesNotContain(FakeTmdbServer.API_KEY).doesNotContain("api_key");
        }
    }

    @Test
    void aTmdbExceptionIsAContentSourceException() {
        assertThat(new TmdbException(TmdbException.Kind.INVALID_INPUT, "x"))
                .isInstanceOf(dev.andre.homecontrol.core.content.ContentSourceException.class);
    }
}

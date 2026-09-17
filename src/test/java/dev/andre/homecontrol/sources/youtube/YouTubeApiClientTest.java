package dev.andre.homecontrol.sources.youtube;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class YouTubeApiClientTest {

    @TempDir
    Path tempDir;

    private FakeGoogleServer fake;
    private GoogleTokens tokens;
    private QuotaLedger ledger;
    private YouTubeApiClient client;

    @BeforeEach
    void setUp() throws IOException {
        fake = new FakeGoogleServer();
        tokens = mock(GoogleTokens.class);
        given(tokens.accessToken()).willReturn("ya29.first", "ya29.second");
        ledger = new QuotaLedger(tempDir.resolve("youtube-quota.json"), MutableClock.at(Instant.parse("2026-09-16T10:00:00Z")),
                10000, 20);
        client = new YouTubeApiClient(new YouTubeHttp(fake.properties()), URI.create(fake.base() + "/youtube/v3"), tokens, ledger);
    }

    @AfterEach
    void closeFake() {
        if (fake != null) {
            fake.close();
        }
    }

    @Test
    void sendsBearerAndQueryInOrder() {
        fake.respond("GET", "/youtube/v3/subscriptions", FakeGoogleServer.Canned.fixture(200, "subscriptions-page-1.json"));
        Map<String, String> query = new LinkedHashMap<>();
        query.put("part", "snippet");
        query.put("mine", "true");
        query.put("maxResults", "50");

        client.get(QuotaLedger.Call.SUBSCRIPTIONS_LIST, "subscriptions", query);

        FakeGoogleServer.Recorded recorded = fake.requests("/youtube/v3/subscriptions").getFirst();
        assertThat(recorded.header("authorization")).isEqualTo("Bearer ya29.first");
        assertThat(ledger.usage().units()).isEqualTo(1);
    }

    @Test
    void retriesOnceAfterA401() {
        fake.respond("GET", "/youtube/v3/channels", FakeGoogleServer.Canned.fixture(401, "error-unauthorized.json"),
                FakeGoogleServer.Canned.fixture(200, "channels-mine.json"));

        JsonNode result = client.get(QuotaLedger.Call.CHANNELS_LIST, "channels", Map.of("part", "snippet", "mine", "true"));

        assertThat(result.path("items").path(0).path("id").asString()).isEqualTo("UC4fixtureHomeControl00a");
        verify(tokens).invalidate();
        assertThat(fake.requests("/youtube/v3/channels")).hasSize(2);
        assertThat(fake.requests("/youtube/v3/channels").get(1).header("authorization")).isEqualTo("Bearer ya29.second");
        // Two real calls reached the Data API (the 401 and the retry that succeeded), so both are charged.
        assertThat(ledger.usage().units()).isEqualTo(2);
    }

    @Test
    void revokedAuthorizationLeavesTheLedgerUnchanged() {
        GoogleTokens revoked = mock(GoogleTokens.class);
        given(revoked.accessToken()).willThrow(new YouTubeException(YouTubeException.Kind.REVOKED,
                "YouTube access was revoked; reconnect YouTube on the setup page"));
        YouTubeApiClient revokedClient = new YouTubeApiClient(new YouTubeHttp(fake.properties()),
                URI.create(fake.base() + "/youtube/v3"), revoked, ledger);

        assertThatThrownBy(() -> revokedClient.get(QuotaLedger.Call.CHANNELS_LIST, "channels", Map.of()))
                .isInstanceOf(YouTubeException.class)
                .extracting(e -> ((YouTubeException) e).kind())
                .isEqualTo(YouTubeException.Kind.REVOKED);
        assertThat(ledger.usage().units()).isZero();
        assertThat(fake.requests("/youtube/v3/channels")).isEmpty();
    }

    @Test
    void aSecond401IsUnauthorized() {
        fake.respond("GET", "/youtube/v3/channels", FakeGoogleServer.Canned.fixture(401, "error-unauthorized.json"));

        assertThatThrownBy(() -> client.get(QuotaLedger.Call.CHANNELS_LIST, "channels", Map.of()))
                .isInstanceOf(YouTubeException.class)
                .extracting(e -> ((YouTubeException) e).kind())
                .isEqualTo(YouTubeException.Kind.UNAUTHORIZED);
    }

    @Test
    void quotaExceededMarksTheLedger() {
        fake.respond("GET", "/youtube/v3/channels", FakeGoogleServer.Canned.fixture(403, "error-quota-exceeded.json"));

        assertThatThrownBy(() -> client.get(QuotaLedger.Call.CHANNELS_LIST, "channels", Map.of()))
                .isInstanceOf(YouTubeException.class)
                .extracting(e -> ((YouTubeException) e).kind())
                .isEqualTo(YouTubeException.Kind.QUOTA_EXHAUSTED);
        assertThat(ledger.usage().exhausted()).isTrue();
    }

    @Test
    void apiNotEnabledIsExplained() {
        fake.respond("GET", "/youtube/v3/channels", FakeGoogleServer.Canned.fixture(403, "error-api-not-enabled.json"));

        assertThatThrownBy(() -> client.get(QuotaLedger.Call.CHANNELS_LIST, "channels", Map.of()))
                .isInstanceOf(YouTubeException.class)
                .extracting(e -> ((YouTubeException) e).kind())
                .isEqualTo(YouTubeException.Kind.FORBIDDEN);
        assertThatThrownBy(() -> client.get(QuotaLedger.Call.CHANNELS_LIST, "channels", Map.of()))
                .hasMessageContaining("not enabled in your Google Cloud project");
    }

    @Test
    void notFoundCarriesTheReason() {
        fake.respond("GET", "/youtube/v3/playlistItems", FakeGoogleServer.Canned.fixture(404, "error-playlist-not-found.json"));

        assertThatThrownBy(() -> client.get(QuotaLedger.Call.PLAYLIST_ITEMS_LIST, "playlistItems", Map.of()))
                .isInstanceOf(YouTubeException.class)
                .extracting(e -> ((YouTubeException) e).kind())
                .isEqualTo(YouTubeException.Kind.NOT_FOUND);
        assertThatThrownBy(() -> client.get(QuotaLedger.Call.PLAYLIST_ITEMS_LIST, "playlistItems", Map.of()))
                .extracting(e -> ((YouTubeException) e).reason())
                .isEqualTo("playlistNotFound");
    }

    @Test
    void serverErrors() {
        fake.respond("GET", "/youtube/v3/channels", new FakeGoogleServer.Canned(503, "text/plain", "oops".getBytes()));

        assertThatThrownBy(() -> client.get(QuotaLedger.Call.CHANNELS_LIST, "channels", Map.of()))
                .isInstanceOf(YouTubeException.class)
                .extracting(e -> ((YouTubeException) e).kind())
                .isEqualTo(YouTubeException.Kind.SERVER_ERROR);
        assertThatThrownBy(() -> client.get(QuotaLedger.Call.CHANNELS_LIST, "channels", Map.of()))
                .hasMessage("YouTube is having problems (HTTP 503)");
    }

    @Test
    void noQuotaNoRequest() {
        QuotaLedger empty = new QuotaLedger(tempDir.resolve("empty-quota.json"), MutableClock.at(Instant.parse("2026-09-16T10:00:00Z")), 0, 20);
        YouTubeApiClient noQuotaClient = new YouTubeApiClient(new YouTubeHttp(fake.properties()),
                URI.create(fake.base() + "/youtube/v3"), tokens, empty);

        assertThatThrownBy(() -> noQuotaClient.get(QuotaLedger.Call.CHANNELS_LIST, "channels", Map.of()))
                .isInstanceOf(YouTubeException.class)
                .extracting(e -> ((YouTubeException) e).kind())
                .isEqualTo(YouTubeException.Kind.QUOTA_EXHAUSTED);
        assertThat(fake.requests("/youtube/v3/channels")).isEmpty();
    }

    @Test
    void messagesNeverContainTheToken() {
        fake.respond("GET", "/youtube/v3/a", FakeGoogleServer.Canned.fixture(401, "error-unauthorized.json"));
        fake.respond("GET", "/youtube/v3/b", FakeGoogleServer.Canned.fixture(403, "error-quota-exceeded.json"));
        fake.respond("GET", "/youtube/v3/c", FakeGoogleServer.Canned.fixture(403, "error-api-not-enabled.json"));
        fake.respond("GET", "/youtube/v3/d", FakeGoogleServer.Canned.fixture(404, "error-playlist-not-found.json"));
        fake.respond("GET", "/youtube/v3/e", new FakeGoogleServer.Canned(503, "text/plain", "oops".getBytes()));

        for (String resource : new String[] {"a", "b", "c", "d", "e"}) {
            assertThatThrownBy(() -> client.get(QuotaLedger.Call.CHANNELS_LIST, resource, Map.of()))
                    .satisfies(e -> assertThat(e.getMessage()).doesNotContain("ya29"));
        }
    }
}

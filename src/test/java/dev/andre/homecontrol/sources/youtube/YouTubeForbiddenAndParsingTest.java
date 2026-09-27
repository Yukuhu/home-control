package dev.andre.homecontrol.sources.youtube;

import dev.andre.homecontrol.testsupport.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/** How each kind of 403 is explained, how a lounge bind answer is scanned, and how a response compares. */
class YouTubeForbiddenAndParsingTest {

    @TempDir
    Path tempDir;

    private FakeGoogleServer fake;
    private QuotaLedger ledger;
    private YouTubeApiClient client;

    @BeforeEach
    void setUp() throws IOException {
        fake = new FakeGoogleServer();
        GoogleTokens tokens = mock(GoogleTokens.class);
        given(tokens.accessToken()).willReturn("ya29.token");
        ledger = new QuotaLedger(tempDir.resolve("youtube-quota.json"), MutableClock.at(Instant.parse("2026-09-16T10:00:00Z")),
                10000, 20);
        client = new YouTubeApiClient(new YouTubeHttp(fake.properties()), URI.create(fake.base() + "/youtube/v3"), tokens, ledger);
    }

    @AfterEach
    void closeFake() {
        fake.close();
    }

    private YouTubeException forbiddenWith(String errorJson) {
        fake.respond("GET", "/youtube/v3/channels", FakeGoogleServer.Canned.json(403, errorJson));
        try {
            client.get(QuotaLedger.Call.CHANNELS_LIST, "channels", Map.of());
        } catch (YouTubeException e) {
            return e;
        }
        throw new AssertionError("a 403 must fail");
    }

    @Test
    void aSpentDailyLimitMarksTheLedgerLikeSpentQuota() {
        YouTubeException failure = forbiddenWith("""
                {"error":{"errors":[{"reason":"dailyLimitExceeded"}]}}
                """);

        assertThat(failure.kind()).isEqualTo(YouTubeException.Kind.QUOTA_EXHAUSTED);
        assertThat(ledger.usage().exhausted()).isTrue();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            {"error":{"status":"PERMISSION_DENIED","message":"The API is disabled for this project"}} | not enabled in your Google Cloud project | ''
            {"error":{"status":"PERMISSION_DENIED","message":"Nope"}}                                 | YouTube refused the request (HTTP 403)  | ''
            {"error":{"status":"OTHER","message":"has not been used"}}                                | YouTube refused the request (HTTP 403)  | ''
            {"error":{"errors":[{"reason":"insufficientPermissions"}]}}                               | reconnect YouTube                       | insufficientPermissions
            {"error":{"errors":[{"reason":"forbidden"}]}}                                             | YouTube refused the request (forbidden) | forbidden
            not json at all                                                                           | YouTube refused the request (HTTP 403)  | ''
            """)
    void everyOtherRefusalIsForbiddenWithItsExplanation(String body, String message, String reason) {
        YouTubeException failure = forbiddenWith(body);

        assertThat(failure.kind()).isEqualTo(YouTubeException.Kind.FORBIDDEN);
        assertThat(failure).hasMessageContaining(message);
        assertThat(failure.reason()).isEqualTo(reason);
        assertThat(ledger.usage().exhausted()).isFalse();
    }

    @Test
    void bracketsAndEscapedQuotesInsideStringsDoNotSplitABindChunk() {
        String body = "40\n[[0,[\"c\",\"sid-1\",\"a ] tricky \\\" ] [ value\"]]]\n20\n[[1,[\"S\",\"gs-1\"]]]\n";

        assertThat(LoungeClient.parseBind(body)).isEqualTo(new LoungeClient.LoungeSession("sid-1", "gs-1", 1));
    }

    @Test
    void unreadableChunksAndEventsThatAreNotArraysAreSkipped() {
        String body = "[[0,[\"c\",\"sid-1\"]],\"text\",7] \"stray\" [not json] [[5,[\"S\",\"gs-1\"]],[6,[\"noop\"]]]";

        assertThat(LoungeClient.parseBind(body)).isEqualTo(new LoungeClient.LoungeSession("sid-1", "gs-1", 6));
    }

    @Test
    void anUnterminatedStringEndsTheScanButKeepsWhatCameBefore() {
        assertThat(LoungeClient.parseBind("[[0,[\"c\",\"sid-1\"]]][[1,[\"S\",\"gs-1\"]]][\"open \\"))
                .isEqualTo(new LoungeClient.LoungeSession("sid-1", "gs-1", 1));
        assertThat(LoungeClient.parseBind("[[0,[\"c\",\"sid-1\"]]][[1,[\"S\",\"gs-1\"]]][\"open"))
                .isEqualTo(new LoungeClient.LoungeSession("sid-1", "gs-1", 1));
    }

    @Test
    void aResponseComparesByItsBodyAndPrintsOnlyItsSize() {
        var response = new YouTubeHttp.Response(200, "application/json", new byte[] {1, 2});
        var same = new YouTubeHttp.Response(200, "application/json", new byte[] {1, 2});

        assertThat(response).isEqualTo(same).hasSameHashCodeAs(same)
                .isNotEqualTo(new YouTubeHttp.Response(201, "application/json", new byte[] {1, 2}))
                .isNotEqualTo(new YouTubeHttp.Response(200, "text/plain", new byte[] {1, 2}))
                .isNotEqualTo(new YouTubeHttp.Response(200, "application/json", new byte[] {1, 3}))
                .isNotEqualTo("application/json")
                .hasToString("Response[status=200, contentType=application/json, body=2 bytes]");
        assertThat(new YouTubeHttp.Response(204, null, null)).hasToString("Response[status=204, contentType=null, body=none]");
    }
}

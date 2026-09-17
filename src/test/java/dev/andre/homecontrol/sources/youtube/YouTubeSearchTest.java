package dev.andre.homecontrol.sources.youtube;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class YouTubeSearchTest {

    @TempDir
    Path tempDir;

    private FakeGoogleServer fake;
    private MutableClock clock;
    private QuotaLedger ledger;
    private KnownVideos known;
    private YouTubeSearch search;

    @BeforeEach
    void setUp() throws IOException {
        fake = new FakeGoogleServer();
        fake.respond("GET", "/youtube/v3/search", FakeGoogleServer.Canned.fixture(200, "search-videos.json"));
        clock = MutableClock.at(Instant.parse("2026-09-16T10:00:00Z"));
        ledger = new QuotaLedger(tempDir.resolve("quota.json"), clock, 10000, 2);
        GoogleTokens tokens = mock(GoogleTokens.class);
        given(tokens.accessToken()).willReturn("ya29.t");
        YouTubeApiClient api = new YouTubeApiClient(new YouTubeHttp(fake.properties()),
                URI.create(fake.base() + "/youtube/v3"), tokens, ledger);
        known = new KnownVideos(1000);
        search = new YouTubeSearch(api, known, fake.properties(), clock);
    }

    @AfterEach
    void closeFake() {
        if (fake != null) {
            fake.close();
        }
    }

    @Test
    void searchesVideosOnce() {
        List<YouTubeVideo> videos = search.search("Big  Bunny", 10);

        assertThat(videos).extracting(YouTubeVideo::id).containsExactly("aqz-KE-bpKQ", "Hh7Lq2Wv9sE");
        assertThat(fake.requests("/youtube/v3/search").getFirst().query()).containsEntry("part", "snippet")
                .containsEntry("type", "video").containsEntry("maxResults", "10").containsEntry("q", "Big  Bunny");
        assertThat(ledger.usage().units()).isEqualTo(100);
        assertThat(ledger.usage().searches()).isEqualTo(1);
    }

    @Test
    void unescapesTitles() {
        List<YouTubeVideo> videos = search.search("bunny", 10);

        assertThat(videos.get(1).title()).isEqualTo("Bunnies & Black Holes: \"Why\" It's Not Fine");
        assertThat(videos.get(1).channelTitle()).isEqualTo("Kurzgesagt &ndash; In a Nutshell");
    }

    @ParameterizedTest
    @CsvSource({
            "a&amp;lt;b, a&lt;b",
            "'&#65;&#x42;', AB",
            "&bogus;, &bogus;",
            "'5 &gt; 3', '5 > 3'"
    })
    void unescapeHtmlCases(String input, String expected) {
        assertThat(YouTubeSearch.unescapeHtml(input)).isEqualTo(expected);
    }

    @Test
    void aRepeatedQueryIsFree() {
        search.search("big bunny", 5);

        clock.advance(Duration.ofHours(1));
        List<YouTubeVideo> second = search.search("  BIG   bunny ", 2);

        assertThat(fake.requests("/youtube/v3/search")).hasSize(1);
        assertThat(second).hasSize(2);
        assertThat(ledger.usage().searches()).isEqualTo(1);
    }

    @Test
    void theCacheExpires() {
        search.search("big bunny", 5);

        clock.advance(Duration.ofHours(6).plusMinutes(1));
        search.search("big bunny", 5);

        assertThat(fake.requests("/youtube/v3/search")).hasSize(2);
    }

    @Test
    void theDailyCapIsEnforced() {
        search.search("one", 5);
        search.search("two", 5);

        assertThatThrownBy(() -> search.search("three", 5))
                .isInstanceOf(YouTubeException.class)
                .extracting(e -> ((YouTubeException) e).kind())
                .isEqualTo(YouTubeException.Kind.SEARCH_LIMIT);
        assertThat(fake.requests("/youtube/v3/search")).hasSize(2);
    }

    @Test
    void limitIsCappedAt25() {
        search.search("x", 50);

        assertThat(fake.requests("/youtube/v3/search").getFirst().query()).containsEntry("maxResults", "25");
    }

    @Test
    void resultsAreRemembered() {
        search.search("bunny", 10);

        assertThat(known.find("Hh7Lq2Wv9sE")).isPresent();
    }

    @Test
    void aLargerLimitIsServedFromTheSmallerCachedAnswer() {
        // Simulates Google actually honoring maxResults=1 for the first call: the cache remembers
        // exactly what came back, and a later, larger limit is served from that smaller answer
        // rather than triggering a second request.
        fake.respond("GET", "/youtube/v3/search", FakeGoogleServer.Canned.json(200, """
                {"kind":"youtube#searchListResponse","items":[
                  {"id":{"kind":"youtube#video","videoId":"aqz-KE-bpKQ"},
                   "snippet":{"title":"Big Buck Bunny","channelTitle":"Blender",
                              "publishedAt":"2014-11-10T14:05:47Z","liveBroadcastContent":"none"}}
                ]}
                """));

        search.search("bunny", 1);
        List<YouTubeVideo> second = search.search("bunny", 10);

        assertThat(fake.requests("/youtube/v3/search")).hasSize(1);
        assertThat(second).hasSize(1);
    }

    @Test
    void failuresAreNotCached() {
        // A generic 403 that does not trip the daily-quota ledger (unlike "quotaExceeded"), so the
        // second, successful call is only checking that a *search* failure isn't cached — not
        // exercising the separate all-APIs-blocked path covered by QuotaLedgerTest/YouTubeApiClientTest.
        fake.respond("GET", "/youtube/v3/search", FakeGoogleServer.Canned.fixture(403, "error-api-not-enabled.json"),
                FakeGoogleServer.Canned.fixture(200, "search-videos.json"));

        assertThatThrownBy(() -> search.search("bunny", 5)).isInstanceOf(YouTubeException.class);
        List<YouTubeVideo> retried = search.search("bunny", 5);

        assertThat(retried).isNotEmpty();
        assertThat(fake.requests("/youtube/v3/search")).hasSize(2);
    }
}

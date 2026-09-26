package dev.andre.homecontrol.sources.youtube;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class SubscriptionsFeedTest {

    private static final String KURZGESAGT = "UCsXVk37bltHxD1rDPwtNM8Q";
    private static final String BLENDER = "UCSMOQeBJ2RAnuFungnQOxLg";
    private static final String NASA = "UCLA_DiR1FfKNvjuUpBHmylQ";

    @TempDir
    Path tempDir;

    private FakeGoogleServer fake;
    private MutableClock clock;
    private GoogleTokens tokens;
    private int ledgerSeq;

    @BeforeEach
    void setUp() throws IOException {
        fake = new FakeGoogleServer().oauthApproves().youtubeLibrary();
        clock = MutableClock.at(Instant.parse("2026-09-16T10:00:00Z"));
        tokens = mock(GoogleTokens.class);
        given(tokens.accessToken()).willReturn("ya29.t");
    }

    @AfterEach
    void closeFake() {
        if (fake != null) {
            fake.close();
        }
    }

    private YouTubeApiClient api() {
        return api(10000);
    }

    private YouTubeApiClient api(int dailyUnits) {
        QuotaLedger ledger = new QuotaLedger(tempDir.resolve("quota-" + (ledgerSeq++) + ".json"), clock, dailyUnits, 20);
        return new YouTubeApiClient(new YouTubeHttp(fake.properties()), URI.create(fake.base() + "/youtube/v3"), tokens, ledger);
    }

    private SubscriptionsFeed feed(YouTubeApiClient client) {
        return new SubscriptionsFeed(client, fake.properties(), clock);
    }

    private SubscriptionsFeed feed(YouTubeApiClient client, YouTubeProperties properties) {
        return new SubscriptionsFeed(client, properties, clock);
    }

    private static List<String> ids(List<YouTubeVideo> videos) {
        return videos.stream().map(YouTubeVideo::id).collect(Collectors.toList());
    }

    @Test
    void buildsTheRailNewestFirst() {
        YouTubeApiClient client = api();
        SubscriptionsFeed feed = feed(client);

        List<YouTubeVideo> videos = feed.refresh();

        assertThat(ids(videos)).containsExactly("Kz1aT5nM3pQ", "Pm6Jd3Fg0kU", "aqz-KE-bpKQ", "Hh7Lq2Wv9sE");
        assertThat(fake.requests("/youtube/v3/subscriptions")).hasSize(2);
        assertThat(fake.requests("/youtube/v3/subscriptions").get(1).query()).containsEntry("pageToken", "CAIQAA");
        List<FakeGoogleServer.Recorded> channelCalls = fake.requests("/youtube/v3/channels").stream()
                .filter(r -> "contentDetails".equals(r.query().get("part"))).toList();
        assertThat(channelCalls).hasSize(1);
        assertThat(channelCalls.getFirst().query()).containsEntry("id", KURZGESAGT + "," + BLENDER + "," + NASA);
        List<FakeGoogleServer.Recorded> playlistCalls = fake.requests("/youtube/v3/playlistItems");
        assertThat(playlistCalls).hasSize(3);
        playlistCalls.forEach(r -> {
            assertThat(r.query()).containsEntry("maxResults", "5").containsEntry("part", "snippet,contentDetails");
        });
    }

    @Test
    void respectsTheMinimumSpacing() {
        YouTubeApiClient client = api();
        SubscriptionsFeed feed = feed(client);
        List<YouTubeVideo> first = feed.refresh();

        clock.advance(Duration.ofMinutes(10));
        List<YouTubeVideo> second = feed.refresh();

        assertThat(second).isEqualTo(first);
        assertThat(fake.requests("/youtube/v3/subscriptions")).hasSize(2);
        assertThat(fake.requests("/youtube/v3/playlistItems")).hasSize(3);

        clock.advance(Duration.ofMinutes(5)); // 15 minutes since the first refresh
        feed.refresh();

        assertThat(fake.requests("/youtube/v3/subscriptions")).hasSize(2);
        assertThat(fake.requests("/youtube/v3/playlistItems")).hasSize(6);
    }

    @Test
    void pollsLeastRecentlyPolledChannelsWithinTheBudget() {
        YouTubeProperties properties = withChannelsPerRefresh(fake.properties(), 2);
        YouTubeApiClient client = api();
        SubscriptionsFeed feed = feed(client, properties);

        feed.refresh();
        assertThat(fake.requests("/youtube/v3/playlistItems").stream().map(r -> r.query().get("playlistId")).toList())
                .containsExactly("UUsXVk37bltHxD1rDPwtNM8Q", "UUSMOQeBJ2RAnuFungnQOxLg");

        clock.advance(Duration.ofMinutes(15));
        List<YouTubeVideo> second = feed.refresh();

        List<String> secondRoundPlaylists = fake.requests("/youtube/v3/playlistItems").stream()
                .skip(2).map(r -> r.query().get("playlistId")).toList();
        assertThat(secondRoundPlaylists).containsExactly("UULA_DiR1FfKNvjuUpBHmylQ", "UUsXVk37bltHxD1rDPwtNM8Q");
        assertThat(ids(second)).contains("Pm6Jd3Fg0kU");
    }

    @Test
    void refreshesTheSubscriptionListDaily() {
        YouTubeApiClient client = api();
        SubscriptionsFeed feed = feed(client);
        List<YouTubeVideo> first = feed.refresh();
        assertThat(ids(first)).contains("aqz-KE-bpKQ");

        clock.advance(Duration.ofHours(24));
        fake.respond("GET", "/youtube/v3/subscriptions", FakeGoogleServer.Canned.json(200, """
                {"kind":"youtube#subscriptionListResponse","items":[
                  {"snippet":{"resourceId":{"channelId":"%s"},"title":"Kurzgesagt – In a Nutshell"}}
                ]}
                """.formatted(KURZGESAGT)));

        List<YouTubeVideo> second = feed.refresh();

        assertThat(fake.requests("/youtube/v3/subscriptions")).hasSize(3);
        assertThat(ids(second)).doesNotContain("aqz-KE-bpKQ");
    }

    @Test
    void aMissingUploadsPlaylistIsRemembered() throws IOException {
        FakeGoogleServer isolated = new FakeGoogleServer().oauthApproves();
        isolated.respondWhen("GET", "/youtube/v3/channels", r -> "true".equals(r.query().get("mine")),
                FakeGoogleServer.Canned.fixture(200, "channels-mine.json"));
        isolated.respondWhen("GET", "/youtube/v3/subscriptions", r -> !r.query().containsKey("pageToken"),
                FakeGoogleServer.Canned.fixture(200, "subscriptions-page-1.json"));
        isolated.respondWhen("GET", "/youtube/v3/subscriptions", r -> "CAIQAA".equals(r.query().get("pageToken")),
                FakeGoogleServer.Canned.fixture(200, "subscriptions-page-2.json"));
        // A channels.list answer missing NASA entirely.
        isolated.respondWhen("GET", "/youtube/v3/channels", r -> "contentDetails".equals(r.query().get("part")),
                FakeGoogleServer.Canned.json(200, """
                        {"kind":"youtube#channelListResponse","items":[
                          {"id":"%s","contentDetails":{"relatedPlaylists":{"uploads":"UUsXVk37bltHxD1rDPwtNM8Q"}}},
                          {"id":"%s","contentDetails":{"relatedPlaylists":{"uploads":"UUSMOQeBJ2RAnuFungnQOxLg"}}}
                        ]}
                        """.formatted(KURZGESAGT, BLENDER)));
        isolated.playlist("UUsXVk37bltHxD1rDPwtNM8Q", "playlist-items-uploads-kurzgesagt.json");
        isolated.playlist("UUSMOQeBJ2RAnuFungnQOxLg", "playlist-items-uploads-blender.json");
        QuotaLedger ledger = new QuotaLedger(tempDir.resolve("missing-uploads.json"), clock, 10000, 20);
        YouTubeApiClient client = new YouTubeApiClient(new YouTubeHttp(isolated.properties()),
                URI.create(isolated.base() + "/youtube/v3"), tokens, ledger);
        SubscriptionsFeed feed = new SubscriptionsFeed(client, isolated.properties(), clock);

        List<YouTubeVideo> first = feed.refresh();
        assertThat(ids(first)).doesNotContain("Pm6Jd3Fg0kU");
        assertThat(isolated.requests("/youtube/v3/channels").stream()
                .filter(r -> "contentDetails".equals(r.query().get("part")))).hasSize(1);

        clock.advance(Duration.ofHours(24));
        feed.refresh();

        assertThat(isolated.requests("/youtube/v3/channels").stream()
                .filter(r -> "contentDetails".equals(r.query().get("part")))).hasSize(1);
        isolated.close();
    }

    @Test
    void aChannelWithoutUploadsIsEmptyNotAnError() {
        fake.respondWhen("GET", "/youtube/v3/playlistItems", r -> "UULA_DiR1FfKNvjuUpBHmylQ".equals(r.query().get("playlistId")),
                FakeGoogleServer.Canned.fixture(404, "error-playlist-not-found.json"));
        YouTubeApiClient client = api();
        SubscriptionsFeed feed = feed(client);

        List<YouTubeVideo> videos = feed.refresh();

        assertThat(ids(videos)).containsExactlyInAnyOrder("Kz1aT5nM3pQ", "Hh7Lq2Wv9sE", "aqz-KE-bpKQ");
    }

    @Test
    void quotaMidwayKeepsWhatItHas() {
        YouTubeApiClient client = api(4);
        SubscriptionsFeed feed = feed(client);

        List<YouTubeVideo> videos = feed.refresh();

        assertThat(ids(videos)).containsExactlyInAnyOrder("Kz1aT5nM3pQ", "Hh7Lq2Wv9sE");
        assertThat(fake.requests()).hasSize(4);
    }

    @Test
    void quotaBeforeAnythingFails() {
        YouTubeApiClient client = api(0);
        SubscriptionsFeed feed = feed(client);

        assertThatThrownBy(feed::refresh)
                .isInstanceOf(YouTubeException.class)
                .extracting(e -> ((YouTubeException) e).kind())
                .isEqualTo(YouTubeException.Kind.QUOTA_EXHAUSTED);
    }

    @Test
    void revokedAuthorizationFails() {
        given(tokens.accessToken()).willThrow(new YouTubeException(YouTubeException.Kind.REVOKED, "revoked"));
        YouTubeApiClient client = api();
        SubscriptionsFeed feed = feed(client);

        assertThatThrownBy(feed::refresh)
                .isInstanceOf(YouTubeException.class)
                .extracting(e -> ((YouTubeException) e).kind())
                .isEqualTo(YouTubeException.Kind.REVOKED);
    }

    @Test
    void anApiThatIsNotEnabledFails() {
        fake.respond("GET", "/youtube/v3/subscriptions", FakeGoogleServer.Canned.fixture(403, "error-api-not-enabled.json"));
        YouTubeApiClient client = api();
        SubscriptionsFeed feed = feed(client);

        assertThatThrownBy(feed::refresh)
                .isInstanceOf(YouTubeException.class)
                .extracting(e -> ((YouTubeException) e).kind())
                .isEqualTo(YouTubeException.Kind.FORBIDDEN);
    }

    @Test
    void aFailedFirstRefreshIsSpacedTooSoRetryHammersItDoesNot() {
        fake.respond("GET", "/youtube/v3/subscriptions", FakeGoogleServer.Canned.fixture(403, "error-api-not-enabled.json"));
        YouTubeApiClient client = api();
        SubscriptionsFeed feed = feed(client);

        assertThatThrownBy(feed::refresh).isInstanceOf(YouTubeException.class);
        int requestsAfterFirstFailure = fake.requests("/youtube/v3/subscriptions").size();

        // Within the spacing window: the same failure is rethrown, without asking again.
        assertThatThrownBy(feed::refresh)
                .isInstanceOf(YouTubeException.class)
                .extracting(e -> ((YouTubeException) e).kind())
                .isEqualTo(YouTubeException.Kind.FORBIDDEN);
        assertThat(fake.requests("/youtube/v3/subscriptions")).hasSize(requestsAfterFirstFailure);

        // Past the window: it is asked again, and can now succeed.
        clock.advance(Duration.ofMinutes(15));
        fake.respondWhen("GET", "/youtube/v3/subscriptions", r -> !r.query().containsKey("pageToken"),
                FakeGoogleServer.Canned.fixture(200, "subscriptions-page-1.json"));
        fake.respondWhen("GET", "/youtube/v3/subscriptions", r -> "CAIQAA".equals(r.query().get("pageToken")),
                FakeGoogleServer.Canned.fixture(200, "subscriptions-page-2.json"));
        List<YouTubeVideo> videos = feed.refresh();

        assertThat(videos).isNotEmpty();
        assertThat(fake.requests("/youtube/v3/subscriptions")).hasSizeGreaterThan(requestsAfterFirstFailure);
    }

    private static YouTubeProperties withChannelsPerRefresh(YouTubeProperties p, int channelsPerRefresh) {
        return new YouTubeProperties(p.enabled(), p.oauthBaseUrl(), p.apiBaseUrl(), p.loungeBaseUrl(), p.thumbnailBaseUrl(),
                p.connectTimeoutSeconds(), p.requestTimeoutSeconds(), p.dailyQuotaUnits(), p.searchesPerDay(), p.railSize(),
                channelsPerRefresh, p.videosPerChannel(), p.subscriptionsRefresh(), p.maxSubscriptionPages(),
                p.refreshInterval(), p.minRefreshSpacing(), p.searchCacheTtl());
    }
}

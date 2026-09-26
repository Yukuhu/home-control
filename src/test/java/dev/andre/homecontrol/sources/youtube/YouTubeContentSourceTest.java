package dev.andre.homecontrol.sources.youtube;

import dev.andre.homecontrol.core.content.ContentSourceException;
import dev.andre.homecontrol.core.content.Rail;
import dev.andre.homecontrol.core.content.RailDescriptor;
import dev.andre.homecontrol.core.playback.PlayableRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class YouTubeContentSourceTest {

    @TempDir
    Path tempDir;

    private FakeGoogleServer fake;
    private MutableClock clock;
    private YouTubeSetupService setup;
    private SubscriptionsFeed feed;
    private YouTubeApiClient api;
    private YouTubePlaylists playlists;
    private YouTubeSearch search;
    private QuotaLedger ledger;
    private KnownVideos known;
    private YouTubeContentSource source;

    @BeforeEach
    void setUp() throws IOException {
        fake = new FakeGoogleServer();
        clock = MutableClock.at(Instant.parse("2026-09-16T10:00:00Z"));
        setup = mock(YouTubeSetupService.class);
        given(setup.settings()).willReturn(YouTubeSettings.EMPTY);
        feed = mock(SubscriptionsFeed.class);
        ledger = new QuotaLedger(tempDir.resolve("quota.json"), clock, 10000, 20);
        GoogleTokens tokens = mock(GoogleTokens.class);
        given(tokens.accessToken()).willReturn("ya29.t");
        api = new YouTubeApiClient(new YouTubeHttp(fake.properties()), URI.create(fake.base() + "/youtube/v3"), tokens, ledger);
        playlists = new YouTubePlaylists(api, fake.properties(), clock);
        known = new KnownVideos(1000);
        search = new YouTubeSearch(api, known, fake.properties(), clock);
        source = new YouTubeContentSource(setup, feed, api, playlists, search, ledger, known, fake.properties(), clock);
    }

    @AfterEach
    void closeFake() {
        if (fake != null) {
            fake.close();
        }
    }

    @Test
    void unavailableWithoutConnection() {
        given(setup.connected()).willReturn(false);

        assertThat(source.available()).isFalse();
        assertThat(source.rails()).isEmpty();
    }

    @Test
    void oneRailWhenConnected() {
        given(setup.connected()).willReturn(true);

        assertThat(source.rails()).containsExactly(new RailDescriptor("youtube", "subscriptions", "New from your subscriptions"));
        assertThat(source.displayName()).isEqualTo("YouTube");
        assertThat(source.defaultRefreshInterval()).isEqualTo(Duration.ofMinutes(60));
    }

    @Test
    void railReturnsItemsAndRemembersThem() {
        given(setup.connected()).willReturn(true);
        YouTubeVideo v1 = new YouTubeVideo("Kz1aT5nM3pQ", "A", "Chan", Instant.parse("2026-09-15T14:00:12Z"));
        YouTubeVideo v2 = new YouTubeVideo("Hh7Lq2Wv9sE", "B", "Chan", Instant.parse("2026-09-01T14:00:00Z"));
        given(feed.refresh()).willReturn(List.of(v1, v2));

        Rail rail = source.rail("subscriptions");

        assertThat(rail.items()).extracting(i -> i.id()).containsExactly("Kz1aT5nM3pQ", "Hh7Lq2Wv9sE");
        assertThat(rail.fetchedAt()).isEqualTo(clock.instant());

        Optional<dev.andre.homecontrol.core.playback.ContentItem> item = source.item("Kz1aT5nM3pQ");
        assertThat(item).isPresent();
        assertThat(fake.requests("/youtube/v3/videos")).isEmpty();
    }

    @Test
    void unknownItemsCostOneVideosCall() {
        fake.respond("GET", "/youtube/v3/videos", FakeGoogleServer.Canned.fixture(200, "videos-by-id.json"));

        Optional<dev.andre.homecontrol.core.playback.ContentItem> item = source.item("Wq9Ze2Lr5tA");

        assertThat(item).isPresent();
        FakeGoogleServer.Recorded recorded = fake.requests("/youtube/v3/videos").getFirst();
        assertThat(recorded.query()).isEqualTo(java.util.Map.of("part", "snippet", "id", "Wq9Ze2Lr5tA"));

        assertThat(source.item("bad")).isEmpty();
        assertThat(fake.requests("/youtube/v3/videos")).hasSize(1);

        fake.respond("GET", "/youtube/v3/videos", FakeGoogleServer.Canned.json(200, "{\"items\":[]}"));
        assertThat(source.item("Zz9Ze2Lr5tA")).isEmpty();
    }

    @Test
    void unknownRailIsIllegal() {
        given(setup.connected()).willReturn(true);
        assertThatThrownBy(() -> source.rail("nope")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void feedFailuresAreContentSourceExceptions() {
        given(setup.connected()).willReturn(true);
        given(feed.refresh()).willThrow(new YouTubeException(YouTubeException.Kind.QUOTA_EXHAUSTED, "no quota left"));

        assertThatThrownBy(() -> source.rail("subscriptions"))
                .isInstanceOf(dev.andre.homecontrol.core.content.ContentSourceException.class)
                .hasMessage("no quota left");
    }

    private static final String EVENING = "PLx0sYbCqOb8TBPRdmBHs5Iftvv9TPboYG";
    private static final String KIDS = "PLx0sYbCqOb8Q_CLZC2BdBSKEEB59BOPUM";

    private static YouTubeSettings settingsWith(boolean watchLater, Map<String, String> playlists) {
        return new YouTubeSettings(Instant.parse("2026-09-16T10:00:00Z"), "chan", "Andre", watchLater, playlists,
                Set.of(), null);
    }

    @Test
    void railsFollowTheSettings() {
        given(setup.connected()).willReturn(true);
        Map<String, String> selected = new LinkedHashMap<>();
        selected.put(EVENING, "Watch this evening");
        selected.put(KIDS, "Kids science");
        given(setup.settings()).willReturn(settingsWith(true, selected));

        assertThat(source.rails()).containsExactly(
                new RailDescriptor("youtube", "subscriptions", "New from your subscriptions"),
                new RailDescriptor("youtube", "watch-later", "Watch Later"),
                new RailDescriptor("youtube", YouTubePlaylists.railId(KIDS), "Kids science"),
                new RailDescriptor("youtube", YouTubePlaylists.railId(EVENING), "Watch this evening"));
    }

    @Test
    void playlistRailServesItsItems() {
        given(setup.connected()).willReturn(true);
        given(setup.settings()).willReturn(settingsWith(false, Map.of(EVENING, "Watch this evening")));
        fake.playlist(EVENING, "playlist-items-playlist.json");

        Rail rail = source.rail(YouTubePlaylists.railId(EVENING));

        assertThat(rail.items()).extracting(i -> i.id()).containsExactly("Wq9Ze2Lr5tA", "Kz1aT5nM3pQ");
        assertThat(known.find("Wq9Ze2Lr5tA")).isPresent();
    }

    @Test
    void aDeselectedPlaylistRailIsUnknown() {
        given(setup.connected()).willReturn(true);

        assertThatThrownBy(() -> source.rail("pl-0000000000000000")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aVanishedPlaylistSaysSo() {
        given(setup.connected()).willReturn(true);
        given(setup.settings()).willReturn(settingsWith(false, Map.of(EVENING, "Watch this evening")));
        fake.respondWhen("GET", "/youtube/v3/playlistItems", r -> EVENING.equals(r.query().get("playlistId")),
                FakeGoogleServer.Canned.fixture(404, "error-playlist-not-found.json"));

        var preparedArg185_0 = YouTubePlaylists.railId(EVENING);
        assertThatThrownBy(() -> source.rail(preparedArg185_0))
                .isInstanceOf(ContentSourceException.class)
                .hasMessage("The playlist “Watch this evening” no longer exists or is private to another"
                        + " account; choose it again on the setup page");
    }

    @Test
    void watchLaterRailExplainsTheRestriction() {
        given(setup.connected()).willReturn(true);
        given(setup.settings()).willReturn(settingsWith(true, Map.of()));
        fake.playlist(YouTubePlaylists.WATCH_LATER_ID, "playlist-items-empty.json");

        assertThatThrownBy(() -> source.rail("watch-later"))
                .isInstanceOf(ContentSourceException.class)
                .hasMessage(YouTubePlaylists.WATCH_LATER_UNAVAILABLE);
    }

    @Test
    void searchIsOnDemandWithANote() {
        assertThat(source.searchable()).isTrue();
        assertThat(source.searchOnDemand()).isTrue();

        MutableClock berlin = new MutableClock(Instant.parse("2026-09-16T10:00:00Z"), ZoneId.of("Europe/Berlin"));
        QuotaLedger berlinLedger = new QuotaLedger(tempDir.resolve("quota-berlin.json"), berlin, 10000, 20);
        YouTubeContentSource berlinSource =
                new YouTubeContentSource(setup, feed, api, playlists, search, berlinLedger, known, fake.properties(), berlin);

        berlinLedger.charge(QuotaLedger.Call.SEARCH_LIST);
        assertThat(berlinSource.searchNote()).contains("19 of 20 YouTube searches left today");

        for (int i = 0; i < 19; i++) {
            berlinLedger.charge(QuotaLedger.Call.SEARCH_LIST);
        }
        assertThat(berlinSource.searchNote()).contains("YouTube searches used up until 09:00");
    }

    @Test
    void searchMapsItems() {
        fake.respond("GET", "/youtube/v3/search", FakeGoogleServer.Canned.fixture(200, "search-videos.json"));

        List<dev.andre.homecontrol.core.playback.ContentItem> items = source.search("bunny", 20);

        assertThat(items).extracting(i -> i.id()).containsExactly("aqz-KE-bpKQ", "Hh7Lq2Wv9sE");
        assertThat(items.getFirst().playables()).containsExactly(
                new PlayableRef.AppLink(YouTubeVideo.watchUrl("aqz-KE-bpKQ"), "youtube"));
    }
}

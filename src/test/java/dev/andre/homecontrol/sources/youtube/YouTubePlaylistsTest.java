package dev.andre.homecontrol.sources.youtube;

import dev.andre.homecontrol.core.content.ContentSourceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class YouTubePlaylistsTest {

    private static final String EVENING = "PLx0sYbCqOb8TBPRdmBHs5Iftvv9TPboYG";
    private static final String KIDS = "PLx0sYbCqOb8Q_CLZC2BdBSKEEB59BOPUM";

    @TempDir
    Path tempDir;

    private FakeGoogleServer fake;
    private MutableClock clock;
    private YouTubePlaylists playlists;

    @BeforeEach
    void setUp() throws IOException {
        fake = new FakeGoogleServer();
        fake.respond("GET", "/youtube/v3/playlists", FakeGoogleServer.Canned.fixture(200, "playlists-mine.json"));
        fake.playlist(EVENING, "playlist-items-playlist.json");
        fake.playlist(YouTubePlaylists.WATCH_LATER_ID, "playlist-items-empty.json");
        clock = MutableClock.at(Instant.parse("2026-09-16T10:00:00Z"));
        GoogleTokens tokens = mock(GoogleTokens.class);
        given(tokens.accessToken()).willReturn("ya29.t");
        QuotaLedger ledger = new QuotaLedger(tempDir.resolve("quota.json"), clock, 10000, 20);
        YouTubeApiClient api = new YouTubeApiClient(new YouTubeHttp(fake.properties()),
                URI.create(fake.base() + "/youtube/v3"), tokens, ledger);
        playlists = new YouTubePlaylists(api, fake.properties(), clock);
    }

    @AfterEach
    void closeFake() {
        if (fake != null) {
            fake.close();
        }
    }

    @Test
    void listsMyPlaylistsByTitle() {
        List<YouTubePlaylists.PlaylistSummary> mine = playlists.mine();

        assertThat(mine).containsExactly(
                new YouTubePlaylists.PlaylistSummary(KIDS, "Kids science", 17),
                new YouTubePlaylists.PlaylistSummary(EVENING, "Watch this evening", 2));
        assertThat(fake.requests("/youtube/v3/playlists").getFirst().query())
                .containsEntry("part", "snippet,contentDetails")
                .containsEntry("mine", "true")
                .containsEntry("maxResults", "50");
        assertThat(playlists.loaded(EVENING)).contains(new YouTubePlaylists.PlaylistSummary(EVENING, "Watch this evening", 2));
    }

    @Test
    void pagesAtMostTen() {
        fake.respond("GET", "/youtube/v3/playlists", FakeGoogleServer.Canned.json(200,
                "{\"items\":[],\"nextPageToken\":\"X\"}"));

        playlists.mine();

        assertThat(fake.requests("/youtube/v3/playlists")).hasSize(10);
    }

    @Test
    void itemsKeepPlaylistOrderAndOwnerChannel() {
        List<YouTubeVideo> videos = playlists.items(EVENING);

        assertThat(videos).extracting(YouTubeVideo::id).containsExactly("Wq9Ze2Lr5tA", "Kz1aT5nM3pQ");
        assertThat(videos.getFirst().channelTitle()).isEqualTo("Blender");
        assertThat(fake.requests("/youtube/v3/playlistItems").getFirst().query()).containsEntry("maxResults", "30");
    }

    @Test
    void railIdsAreLowerCaseAndStable() throws Exception {
        String railId = YouTubePlaylists.railId(EVENING);

        assertThat(railId).matches("^pl-[0-9a-f]{16}$");
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(EVENING.getBytes(StandardCharsets.UTF_8));
        String expected = "pl-" + HexFormat.of().formatHex(digest).substring(0, 16);
        assertThat(railId).isEqualTo(expected)
                .isNotEqualTo(YouTubePlaylists.railId(KIDS));
        assertThat(("youtube/" + railId)).matches("^[a-z0-9][a-z0-9._-]{0,63}/[a-z0-9][a-z0-9._-]{0,63}$");
    }

    @Test
    void watchLaterIsHonest() {
        assertThatThrownBy(playlists::watchLater)
                .isInstanceOf(ContentSourceException.class)
                .hasMessage(YouTubePlaylists.WATCH_LATER_UNAVAILABLE);

        clock.advance(Duration.ofMinutes(15));
        fake.respond("GET", "/youtube/v3/playlistItems", FakeGoogleServer.Canned.fixture(404, "error-playlist-not-found.json"));
        assertThatThrownBy(playlists::watchLater)
                .isInstanceOf(ContentSourceException.class)
                .hasMessage(YouTubePlaylists.WATCH_LATER_UNAVAILABLE);

        clock.advance(Duration.ofMinutes(15));
        fake.respond("GET", "/youtube/v3/playlistItems", FakeGoogleServer.Canned.fixture(200, "playlist-items-playlist.json"));
        assertThat(playlists.watchLater()).extracting(YouTubeVideo::id).containsExactly("Wq9Ze2Lr5tA", "Kz1aT5nM3pQ");
    }

    @Test
    void watchLaterFailuresAreSpacedFifteenMinutes() {
        assertThatThrownBy(playlists::watchLater).isInstanceOf(ContentSourceException.class);
        int requestsAfterFirstFailure = fake.requests("/youtube/v3/playlistItems").size();

        // Within the spacing window: the memoized failure is rethrown honestly, at no extra cost.
        fake.respond("GET", "/youtube/v3/playlistItems", FakeGoogleServer.Canned.fixture(200, "playlist-items-playlist.json"));
        assertThatThrownBy(playlists::watchLater)
                .isInstanceOf(ContentSourceException.class)
                .hasMessage(YouTubePlaylists.WATCH_LATER_UNAVAILABLE);
        assertThat(fake.requests("/youtube/v3/playlistItems")).hasSize(requestsAfterFirstFailure);

        // Past the window: it is asked again, and can now succeed.
        clock.advance(Duration.ofMinutes(15));
        assertThat(playlists.watchLater()).extracting(YouTubeVideo::id).containsExactly("Wq9Ze2Lr5tA", "Kz1aT5nM3pQ");
        assertThat(fake.requests("/youtube/v3/playlistItems")).hasSizeGreaterThan(requestsAfterFirstFailure);
    }

    @Test
    void railsAreMemoizedWithinTheSpacing() {
        playlists.items(EVENING);
        clock.advance(Duration.ofMinutes(5));
        playlists.items(EVENING);
        assertThat(fake.requests("/youtube/v3/playlistItems")).hasSize(1);

        clock.advance(Duration.ofMinutes(15));
        playlists.items(EVENING);
        assertThat(fake.requests("/youtube/v3/playlistItems")).hasSize(2);

        String other = "PLnotFoundOnce0000000000000000";
        fake.respondWhen("GET", "/youtube/v3/playlistItems", r -> other.equals(r.query().get("playlistId")),
                FakeGoogleServer.Canned.fixture(404, "error-playlist-not-found.json"),
                FakeGoogleServer.Canned.fixture(200, "playlist-items-playlist.json"));
        assertThatThrownBy(() -> playlists.items(other)).isInstanceOf(YouTubeException.class);
        List<YouTubeVideo> retried = playlists.items(other);
        assertThat(retried).extracting(YouTubeVideo::id).containsExactly("Wq9Ze2Lr5tA", "Kz1aT5nM3pQ");
        assertThat(fake.requests("/youtube/v3/playlistItems").stream().filter(r -> other.equals(r.query().get("playlistId"))))
                .hasSize(2);
    }
}

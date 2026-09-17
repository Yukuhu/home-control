package dev.andre.homecontrol.sources.youtube;

import dev.andre.homecontrol.core.content.Rail;
import dev.andre.homecontrol.core.content.RailDescriptor;
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
import java.util.Optional;

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
    private KnownVideos known;
    private YouTubeContentSource source;

    @BeforeEach
    void setUp() throws IOException {
        fake = new FakeGoogleServer();
        clock = MutableClock.at(Instant.parse("2026-09-16T10:00:00Z"));
        setup = mock(YouTubeSetupService.class);
        feed = mock(SubscriptionsFeed.class);
        QuotaLedger ledger = new QuotaLedger(tempDir.resolve("quota.json"), clock, 10000, 20);
        GoogleTokens tokens = mock(GoogleTokens.class);
        given(tokens.accessToken()).willReturn("ya29.t");
        api = new YouTubeApiClient(new YouTubeHttp(fake.properties()), URI.create(fake.base() + "/youtube/v3"), tokens, ledger);
        known = new KnownVideos(1000);
        source = new YouTubeContentSource(setup, feed, api, known, fake.properties(), clock);
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
}

package dev.andre.homecontrol.sources.tmdb;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TmdbWatchProvidersTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    FakeTmdbServer fake;
    TmdbClient client;
    TmdbProperties properties;
    MutableClock clock;
    TmdbWatchProviders providers;
    TmdbCredential bearer;

    @BeforeEach
    void setUp() throws IOException {
        fake = new FakeTmdbServer().withStandardResponses();
        properties = new TmdbProperties(true, fake.apiBase(), null, 1, 1, 20, 40,
                Duration.ofHours(24), Duration.ofHours(24), null);
        client = new TmdbClient(properties);
        clock = MutableClock.at(Instant.parse("2026-09-16T10:00:00Z"));
        providers = new TmdbWatchProviders(client, properties, clock);
        bearer = TmdbCredential.parse(FakeTmdbServer.READ_TOKEN);
    }

    @AfterEach
    void tearDown() {
        fake.close();
    }

    @Test
    void parsesEveryCategoryForTheRegion() {
        JsonNode json = JSON.readTree(FakeTmdbServer.fixture("providers-movie-603.json"));

        List<WatchProvider> found = WatchProvider.parse(json, "DE");

        assertThat(found).containsExactly(
                new WatchProvider(30, "WOW", WatchProvider.Category.FLATRATE, 12),
                new WatchProvider(2, "Apple TV", WatchProvider.Category.RENT, 4),
                new WatchProvider(10, "Amazon Video", WatchProvider.Category.BUY, 20));
    }

    @Test
    void aMissingRegionIsEmpty() {
        JsonNode json = JSON.readTree(FakeTmdbServer.fixture("providers-tv-94997.json"));

        assertThat(WatchProvider.parse(json, "DE")).isEmpty();
        assertThat(WatchProvider.parse(json, "us")).isNotEmpty();
    }

    @Test
    void skipsEntriesWithoutIdOrName() {
        JsonNode json = JSON.readTree(
                "{\"results\":{\"DE\":{\"flatrate\":[{\"provider_id\":0,\"provider_name\":\"\"}]}}}");

        assertThat(WatchProvider.parse(json, "DE")).isEmpty();
    }

    @Test
    void onlySubscriptionCategoriesCount() {
        assertThat(WatchProvider.Category.FLATRATE.subscription()).isTrue();
        assertThat(WatchProvider.Category.FREE.subscription()).isTrue();
        assertThat(WatchProvider.Category.ADS.subscription()).isTrue();
        assertThat(WatchProvider.Category.RENT.subscription()).isFalse();
        assertThat(WatchProvider.Category.BUY.subscription()).isFalse();
    }

    @Test
    void looksUpOnceAndCaches() {
        TmdbMediaRef ref = new TmdbMediaRef(TmdbMediaRef.Type.TV, 66732);

        providers.providers(bearer, ref, "DE");
        providers.providers(bearer, ref, "DE");
        assertThat(fake.count("GET", "/3/tv/66732/watch/providers")).isEqualTo(1);

        clock.advance(properties.providerCacheTtl().plusMinutes(1));
        providers.providers(bearer, ref, "DE");
        assertThat(fake.count("GET", "/3/tv/66732/watch/providers")).isEqualTo(2);
    }

    @Test
    void rememberedDetailsAvoidALookup() {
        TmdbMediaRef ref = new TmdbMediaRef(TmdbMediaRef.Type.TV, 66732);
        JsonNode details = JSON.readTree(FakeTmdbServer.fixture("details-tv-66732.json"));

        providers.remember(ref, details.path("watch/providers"));
        providers.providers(bearer, ref, "DE");

        assertThat(fake.count("GET", "/3/tv/66732/watch/providers")).isZero();
    }

    @Test
    void failuresAreNotCached() {
        TmdbMediaRef ref = new TmdbMediaRef(TmdbMediaRef.Type.TV, 66732);
        fake.respondJson("GET", "/3/tv/66732/watch/providers", 500, "{}");

        assertThatThrownBy(() -> providers.providers(bearer, ref, "DE")).isInstanceOf(TmdbException.class);

        fake.respond("GET", "/3/tv/66732/watch/providers", 200, "providers-tv-66732.json");
        assertThat(providers.providers(bearer, ref, "DE")).isNotEmpty();
    }
}

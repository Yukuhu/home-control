package dev.andre.homecontrol.sources.tmdb;

import dev.andre.homecontrol.core.content.ContentSourceException;
import dev.andre.homecontrol.core.content.SourcePreferences;
import dev.andre.homecontrol.core.playback.ContentItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class TmdbContentSourceTest {

    FakeTmdbServer fake;
    TmdbProperties properties;
    TmdbClient client;
    TmdbImages images;
    TmdbWatchProviders providers;
    TmdbCredential bearer;

    @BeforeEach
    void setUp() throws IOException {
        fake = new FakeTmdbServer().withStandardResponses();
        properties = new TmdbProperties(true, fake.apiBase(), null, 1, 2, 20, 40,
                Duration.ofHours(24), Duration.ofHours(24), null);
        client = new TmdbClient(properties);
        images = new TmdbImages(client, properties, Clock.systemUTC());
        providers = new TmdbWatchProviders(client, properties, Clock.systemUTC());
        bearer = TmdbCredential.parse(FakeTmdbServer.READ_TOKEN);
    }

    @AfterEach
    void tearDown() {
        fake.close();
    }

    private TmdbContentSource source(TmdbSetupService setup) {
        return new TmdbContentSource(setup, client, images, providers, properties,
                () -> SourcePreferences.defaults("de-DE", "DE"));
    }

    private TmdbSetupService connectedSetup() {
        TmdbSetupService setup = mock(TmdbSetupService.class);
        given(setup.credential()).willReturn(java.util.Optional.of(bearer));
        return setup;
    }

    @Test
    void isAvailableOnlyWhenConnected() {
        TmdbSetupService setup = mock(TmdbSetupService.class);
        given(setup.credential()).willReturn(java.util.Optional.empty());
        TmdbContentSource unavailable = source(setup);
        assertThat(unavailable.available()).isFalse();
        assertThat(unavailable.rails()).isEmpty();

        TmdbContentSource available = source(connectedSetup());
        assertThat(available.available()).isTrue();
    }

    @Test
    void searchesMultiInTheUsersLanguage() {
        TmdbContentSource source = source(connectedSetup());

        java.util.List<ContentItem> items = source.search("matrix", 10);

        assertThat(items).extracting(ContentItem::title).containsExactly("Matrix", "Matrix Reloaded", "The Animatrix");
        FakeTmdbServer.Recorded recorded = fake.last("GET", "/3/search/multi");
        assertThat(recorded.query()).containsEntry("query", "matrix").containsEntry("language", "de-DE")
                .containsEntry("include_adult", "false").containsEntry("page", "1");
        assertThat(items.get(0).artwork().toString()).startsWith("https://image.tmdb.org/t/p/w342/");
    }

    @Test
    void searchRespectsTheLimit() {
        TmdbContentSource source = source(connectedSetup());

        assertThat(source.search("matrix", 2)).hasSize(2);
    }

    @Test
    void searchFailuresAreContentSourceExceptions() {
        fake.respondJson("GET", "/3/search/multi", 429, "{}");
        TmdbContentSource source = source(connectedSetup());

        assertThatThrownBy(() -> source.search("matrix", 10))
                .isInstanceOf(ContentSourceException.class)
                .hasMessage("TMDB is limiting requests; try again in a moment");
    }

    @Test
    void readsAnItemWithItsProviders() {
        TmdbContentSource source = source(connectedSetup());

        ContentItem item = source.item("tv-66732").orElseThrow();

        assertThat(item.title()).isEqualTo("Stranger Things");
        assertThat(item.subtitle()).isEqualTo("Series · 2016");
        FakeTmdbServer.Recorded recorded = fake.last("GET", "/3/tv/66732");
        assertThat(recorded.query()).containsEntry("language", "de-DE").containsEntry("append_to_response", "watch/providers");

        providers.providers(bearer, new TmdbMediaRef(TmdbMediaRef.Type.TV, 66732), "DE");
        assertThat(fake.count("GET", "/3/tv/66732/watch/providers")).isZero();
    }

    @Test
    void unknownOrMalformedItemsAreEmpty() {
        TmdbContentSource source = source(connectedSetup());

        assertThat(source.item("movie-1")).isEmpty();
        int requestsAfterTheNotFoundLookup = fake.requests().size();

        // "person-1" never names a movie or a series, so TmdbMediaRef.parse rejects it before any
        // HTTP call is made — assert that directly (a request count for a made-up path would be
        // trivially zero regardless of whether the code under test does the right thing).
        assertThat(source.item("person-1")).isEmpty();
        assertThat(fake.requests()).hasSize(requestsAfterTheNotFoundLookup);
    }

    @Test
    void itemFailuresOtherThanNotFoundPropagate() {
        fake.respondJson("GET", "/3/movie/603", 500, "{}");
        TmdbContentSource source = source(connectedSetup());

        assertThatThrownBy(() -> source.item("movie-603")).isInstanceOf(ContentSourceException.class);
    }

    @Test
    void hasNoRailsYetAndRefreshesEverySixHours() {
        TmdbContentSource source = source(connectedSetup());

        assertThat(source.rails()).isEmpty();
        assertThatThrownBy(() -> source.rail("trending")).isInstanceOf(IllegalArgumentException.class);
        assertThat(source.defaultRefreshInterval()).isEqualTo(Duration.ofHours(6));
    }
}

package dev.andre.homecontrol.sources.tmdb;

import dev.andre.homecontrol.core.content.ContentSourceException;
import dev.andre.homecontrol.core.content.PinnedLinks;
import dev.andre.homecontrol.core.content.SourcePreferences;
import dev.andre.homecontrol.core.playback.ContentItem;
import dev.andre.homecontrol.core.playback.PlayableRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class TmdbTrendingRailTest {

    FakeTmdbServer fake;
    TmdbClient client;
    TmdbImages images;
    TmdbWatchProviders providers;
    ProviderMatcher matcher;
    TmdbCredential bearer;
    TmdbSetupService setup;
    Map<String, PlayableRef.AppLink> pinned = new HashMap<>();
    ObjectProvider<PinnedLinks> pinnedLinks;

    String locale = "de-DE";
    String region = "DE";
    List<String> providersConfigured = List.of();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws IOException {
        fake = new FakeTmdbServer().withStandardResponses();
        matcher = new ProviderMatcher(TmdbProperties.DEFAULT_PROVIDER_IDS);
        bearer = TmdbCredential.parse(FakeTmdbServer.READ_TOKEN);
        setup = mock(TmdbSetupService.class);
        given(setup.credential()).willReturn(Optional.of(bearer));
        pinnedLinks = mock(ObjectProvider.class);
        PinnedLinks lookup = (sourceId, itemId) -> Optional.ofNullable(pinned.get(sourceId + "/" + itemId));
        given(pinnedLinks.getIfAvailable()).willReturn(lookup);
    }

    @AfterEach
    void tearDown() {
        fake.close();
    }

    private TmdbProperties properties(int railSize, int trendingCandidates) {
        return new TmdbProperties(true, fake.apiBase(), null, 1, 2, railSize, trendingCandidates,
                Duration.ofHours(24), Duration.ofHours(24), null);
    }

    private TmdbContentSource source(TmdbProperties properties) {
        TmdbClient localClient = new TmdbClient(properties);
        TmdbImages localImages = new TmdbImages(localClient, properties, Clock.systemUTC());
        TmdbWatchProviders localProviders = new TmdbWatchProviders(localClient, properties, Clock.systemUTC());
        return new TmdbContentSource(setup, localClient, localImages, localProviders, properties,
                () -> new SourcePreferences(List.of(), Set.of(), Set.of(), Map.of(), locale, region, providersConfigured),
                matcher, pinnedLinks, Clock.fixed(Instant.parse("2026-09-16T10:00:00Z"), ZoneOffset.UTC));
    }

    private TmdbContentSource source() {
        return source(properties(20, 40));
    }

    @Test
    void listsOnlyTitlesOnTheHouseholdsServices() {
        providersConfigured = List.of("netflix", "primevideo");

        var rail = source().rail("trending");

        assertThat(rail.items()).extracting(ContentItem::title).containsExactly("Stranger Things", "The Boys");
        assertThat(rail.items()).extracting(ContentItem::subtitle)
                .containsExactly("On Netflix · 2016", "On Prime Video · 2019");
        assertThat(rail.items().get(0).playables()).containsExactly(
                new PlayableRef.AppLink(URI.create("https://www.netflix.com/browse"), "netflix"));
        assertThat(rail.items().get(1).playables()).containsExactly(
                new PlayableRef.AppLink(URI.create("https://app.primevideo.com/"), "primevideo"));
        assertThat(rail.items().get(0).artwork()).isNotNull();

        FakeTmdbServer.Recorded trending = fake.last("GET", "/3/trending/all/week");
        assertThat(trending.query()).containsEntry("language", "de-DE").containsEntry("page", "1");
        assertThat(fake.requests()).noneMatch(r -> r.path().contains("/person/"));
    }

    @Test
    void providersWithoutLauncherHaveNoPlayables() {
        providersConfigured = List.of("wowtv");

        var rail = source().rail("trending");

        assertThat(rail.items()).hasSize(1);
        assertThat(rail.items().getFirst().title()).isEqualTo("Matrix");
        assertThat(rail.items().getFirst().subtitle()).isEqualTo("On WOW · 1999");
        assertThat(rail.items().getFirst().playables()).isEmpty();
    }

    @Test
    void regionComesFromPreferences() {
        region = "US";
        providersConfigured = List.of("netflix");

        var rail = source().rail("trending");

        assertThat(rail.items()).extracting(ContentItem::title).containsExactly("Stranger Things");
    }

    @Test
    void aPinnedLinkReplacesTheAppHome() {
        providersConfigured = List.of("netflix");
        pinned.put("tmdb/tv-66732", new PlayableRef.AppLink(URI.create("https://www.netflix.com/title/80057281"), "netflix"));

        var rail = source().rail("trending");

        assertThat(rail.items().getFirst().playables()).containsExactly(
                new PlayableRef.AppLink(URI.create("https://www.netflix.com/title/80057281"), "netflix"));
    }

    @Test
    void respectsRailSizeAndCandidates() {
        providersConfigured = List.of("netflix", "primevideo");

        TmdbContentSource smallRail = source(properties(1, 40));
        assertThat(smallRail.rail("trending").items()).hasSize(1);

        TmdbContentSource fewCandidates = source(properties(20, 2));
        fewCandidates.rail("trending");
        assertThat(fake.count("GET", "/3/tv/76479/watch/providers")).isZero();
    }

    @Test
    void pagesUntilTotalPages() {
        providersConfigured = List.of("netflix");

        source(properties(20, 40)).rail("trending");

        assertThat(fake.count("GET", "/3/trending/all/week")).isEqualTo(1);
    }

    @Test
    void withoutProvidersTheRailExplainsWhatToDo() {
        providersConfigured = List.of();

        var preparedReceiver160 = source();
        assertThatThrownBy(() -> preparedReceiver160.rail("trending"))
                .isInstanceOf(ContentSourceException.class)
                .hasMessage("Choose your streaming services in Setup to see what is trending on them");
        assertThat(fake.requests()).isEmpty();
    }

    @Test
    void oneFailingLookupSkipsThatTitle() {
        providersConfigured = List.of("netflix", "primevideo");
        fake.respondJson("GET", "/3/tv/66732/watch/providers", 500, "{}");

        var rail = source().rail("trending");

        assertThat(rail.items()).extracting(ContentItem::title).containsExactly("The Boys");
    }

    @Test
    void allLookupsFailingFailsTheRail() {
        providersConfigured = List.of("netflix", "primevideo", "wowtv");
        fake.respondJson("GET", "/3/tv/66732/watch/providers", 500, "{}");
        fake.respondJson("GET", "/3/movie/603/watch/providers", 500, "{}");
        fake.respondJson("GET", "/3/tv/76479/watch/providers", 500, "{}");
        fake.respondJson("GET", "/3/movie/550/watch/providers", 500, "{}");
        fake.respondJson("GET", "/3/tv/94997/watch/providers", 500, "{}");

        var preparedReceiver185 = source();
        assertThatThrownBy(() -> preparedReceiver185.rail("trending"))
                .isInstanceOf(TmdbException.class)
                .hasMessage("TMDB had a server error (HTTP 500)");
    }

    @Test
    void theRailTitleNeverClaimsAPersonalFeed() {
        providersConfigured = List.of("netflix", "primevideo");
        TmdbContentSource source = source();

        assertThat(source.rails().getFirst().title()).isEqualTo("Trending on your services");
        var rail = source.rail("trending");
        assertThat(rail.items()).noneSatisfy(item -> assertThat(item.subtitle())
                .containsAnyOf("Continue", "For you", "Recommended", "watching"));
    }

    @Test
    void itemReadsCarryTheSamePlayables() {
        TmdbContentSource source = source();

        providersConfigured = List.of("netflix");
        ContentItem withNetflix = source.item("tv-66732").orElseThrow();
        assertThat(withNetflix.subtitle()).isEqualTo("On Netflix · 2016");
        assertThat(withNetflix.playables()).containsExactly(
                new PlayableRef.AppLink(URI.create("https://www.netflix.com/browse"), "netflix"));

        providersConfigured = List.of("primevideo");
        ContentItem withPrime = source.item("tv-66732").orElseThrow();
        assertThat(withPrime.subtitle()).isEqualTo("Series · 2016");
        assertThat(withPrime.playables()).isEmpty();

        pinned.put("tmdb/tv-66732", new PlayableRef.AppLink(URI.create("https://www.netflix.com/title/80057281"), "netflix"));
        ContentItem withPin = source.item("tv-66732").orElseThrow();
        assertThat(withPin.playables()).containsExactly(
                new PlayableRef.AppLink(URI.create("https://www.netflix.com/title/80057281"), "netflix"));
    }
}

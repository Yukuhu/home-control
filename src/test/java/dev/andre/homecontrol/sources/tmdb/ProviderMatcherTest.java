package dev.andre.homecontrol.sources.tmdb;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderMatcherTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ProviderMatcher matcher = new ProviderMatcher(TmdbProperties.DEFAULT_PROVIDER_IDS);

    @Test
    void matchesByIdFirst() {
        assertThat(matcher.keyOf(new WatchProvider(8, "Anything", WatchProvider.Category.FLATRATE, 0))).contains("netflix");
        assertThat(matcher.keyOf(new WatchProvider(119, "x", WatchProvider.Category.FLATRATE, 0))).contains("primevideo");
        assertThat(matcher.keyOf(new WatchProvider(2100, "x", WatchProvider.Category.ADS, 0))).contains("primevideo");
    }

    @ParameterizedTest
    @CsvSource({
            "Netflix basic with Ads, netflix",
            "Amazon Prime Video, primevideo",
            "DAZN, dazn",
            "Disney Plus, disneyplus",
            "Apple TV Plus, appletvplus",
            "'Apple TV+', appletvplus",
            "Paramount Plus, paramountplus",
            "'Paramount+', paramountplus",
            "WOW, wowtv",
            "Joyn Plus, joyn",
            "'RTL+', rtlplus"
    })
    void matchesByNormalisedName(String name, String key) {
        assertThat(matcher.keyOf(new WatchProvider(99999, name, WatchProvider.Category.FLATRATE, 0))).contains(key);
    }

    @ParameterizedTest
    @CsvSource({
            "'Paramount+ Amazon Channel'",
            "'MGM Plus Amazon Channel'",
            "'Amazon Video'",
            "'Apple TV'",
            "Max",
            "'Sky Go'",
            "Wowow"
    })
    void neverMatchesChannelsOrLookAlikes(String name) {
        assertThat(matcher.keyOf(new WatchProvider(99999, name, WatchProvider.Category.FLATRATE, 0))).isEmpty();
    }

    private static List<dev.andre.homecontrol.sources.tmdb.WatchProvider> providersFrom(String fixture, String region) {
        JsonNode json = JSON.readTree(FakeTmdbServer.fixture(fixture));
        return WatchProvider.parse(json, region);
    }

    @Test
    void onlySubscriptionsCountAndConfiguredOrderWins() {
        assertThat(matcher.matches(providersFrom("providers-tv-76479.json", "DE"), List.of("netflix", "primevideo")))
                .containsExactly("primevideo");

        assertThat(matcher.matches(providersFrom("providers-movie-603.json", "DE"), List.of("primevideo", "wowtv")))
                .containsExactly("wowtv");

        assertThat(matcher.matches(providersFrom("providers-movie-550.json", "DE"), List.of("primevideo")))
                .isEmpty();

        List<WatchProvider> netflixAndPrimeAds = List.of(
                new WatchProvider(8, "Netflix", WatchProvider.Category.FLATRATE, 0),
                new WatchProvider(9, "Amazon Prime Video", WatchProvider.Category.ADS, 0));
        assertThat(matcher.matches(netflixAndPrimeAds, List.of("primevideo", "netflix")))
                .containsExactly("primevideo", "netflix");
    }

    @Test
    void configuredIdsReplaceTheDefaults() {
        ProviderMatcher custom = new ProviderMatcher(Map.of("joyn", List.of(304)));

        assertThat(custom.keyOf(new WatchProvider(304, "Joyn", WatchProvider.Category.FLATRATE, 0))).contains("joyn");
        assertThat(custom.keyOf(new WatchProvider(8, "Some name", WatchProvider.Category.FLATRATE, 0))).isEmpty();
    }
}

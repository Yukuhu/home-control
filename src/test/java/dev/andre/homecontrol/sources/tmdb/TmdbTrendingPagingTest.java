package dev.andre.homecontrol.sources.tmdb;

import dev.andre.homecontrol.core.content.PinnedLinks;
import dev.andre.homecontrol.core.content.SourcePreferences;
import dev.andre.homecontrol.core.playback.ContentItem;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/** Which trending results become rail candidates, and when paging stops. */
class TmdbTrendingPagingTest {

    private static final String STRANGER_THINGS = """
            { "adult": false, "id": 66732, "name": "Stranger Things", "media_type": "tv",
              "poster_path": "/49WJfeN0moxb9IPfGn8AIqMGskD.jpg", "first_air_date": "2016-07-15" }""";

    private FakeTmdbServer fake;
    private TmdbSetupService setup;
    private ObjectProvider<PinnedLinks> pinnedLinks;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws IOException {
        fake = new FakeTmdbServer().withStandardResponses();
        setup = mock(TmdbSetupService.class);
        given(setup.credential()).willReturn(Optional.of(TmdbCredential.parse(FakeTmdbServer.READ_TOKEN)));
        pinnedLinks = mock(ObjectProvider.class);
    }

    @AfterEach
    void tearDown() {
        fake.close();
    }

    private TmdbContentSource source() {
        TmdbProperties properties = new TmdbProperties(true, fake.apiBase(), URI.create("https://img.example/t/p"),
                Duration.ofSeconds(1), Duration.ofSeconds(2), 20, 40, Duration.ofHours(24), Duration.ofHours(24), null, true);
        TmdbClient client = new TmdbClient(properties);
        return new TmdbContentSource(setup, client, new TmdbImages(client, properties, Clock.systemUTC()),
                new TmdbWatchProviders(client, properties, Clock.systemUTC()), properties,
                () -> new SourcePreferences(List.of(), Set.of(), Set.of(), Map.of(), "en-US", "DE", List.of("netflix")),
                new ProviderMatcher(TmdbProperties.DEFAULT_PROVIDER_IDS), pinnedLinks,
                Clock.fixed(Instant.parse("2026-09-16T10:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void skipsAdultTitlesAndRepeatsUntilTheLastPage() {
        fake.respondJson("GET", "/3/trending/all/week", 200, """
                { "page": 1, "total_pages": 2, "results": [
                  { "adult": true, "id": 603, "title": "Matrix", "media_type": "movie" },
                  %s,
                  %s
                ] }""".formatted(STRANGER_THINGS, STRANGER_THINGS));

        List<ContentItem> items = source().rail("trending").items();

        assertThat(items).extracting(ContentItem::title).containsExactly("Stranger Things");
        assertThat(items.getFirst().artwork())
                .isEqualTo(URI.create("https://img.example/t/p/w342/49WJfeN0moxb9IPfGn8AIqMGskD.jpg"));
        assertThat(fake.count("GET", "/3/trending/all/week")).isEqualTo(2);
        assertThat(fake.last("GET", "/3/trending/all/week").query()).containsEntry("page", "2");
        assertThat(fake.count("GET", "/3/movie/603/watch/providers")).isZero();
    }

    @Test
    void anEmptyPageEndsPaging() {
        fake.respondJson("GET", "/3/trending/all/week", 200, """
                { "page": 1, "total_pages": 5, "results": [] }""");

        assertThat(source().rail("trending").items()).isEmpty();
        assertThat(fake.count("GET", "/3/trending/all/week")).isEqualTo(1);
    }
}

package dev.andre.homecontrol.sources.tmdb;

import dev.andre.homecontrol.core.playback.ContentItem;
import dev.andre.homecontrol.core.playback.ContentKind;
import dev.andre.homecontrol.core.playback.PlayableRef;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TmdbItemMapperTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static java.util.function.Function<String, URI> posters() {
        return p -> URI.create("https://image.tmdb.org/t/p/w342" + p);
    }

    private JsonNode result(String fixture, int index) {
        return JSON.readTree(FakeTmdbServer.fixture(fixture)).path("results").get(index);
    }

    @Test
    void mapsAMovie() {
        Optional<ContentItem> item = TmdbItemMapper.toItem(result("search-multi.json", 0), null, posters(), null, List.of());

        ContentItem matrix = item.orElseThrow();
        assertThat(matrix.id()).isEqualTo("movie-603");
        assertThat(matrix.sourceId()).isEqualTo("tmdb");
        assertThat(matrix.kind()).isEqualTo(ContentKind.MOVIE);
        assertThat(matrix.title()).isEqualTo("Matrix");
        assertThat(matrix.subtitle()).isEqualTo("Movie · 1999");
        assertThat(matrix.artwork().toString()).endsWith("/w342/f89U3ADr1oiB1s9GkdPOEpXUk5H.jpg");
        assertThat(matrix.progress()).isNull();
        assertThat(matrix.playables()).isEmpty();
    }

    @Test
    void mapsASeries() {
        Optional<ContentItem> item = TmdbItemMapper.toItem(result("trending-all-week.json", 0), null, posters(), null, List.of());

        ContentItem strangerThings = item.orElseThrow();
        assertThat(strangerThings.id()).isEqualTo("tv-66732");
        assertThat(strangerThings.kind()).isEqualTo(ContentKind.VIDEO);
        assertThat(strangerThings.subtitle()).isEqualTo("Series · 2016");
    }

    @Test
    void fallsBackToTheOriginalTitleAndOmitsAMissingYear() {
        Optional<ContentItem> item = TmdbItemMapper.toItem(result("search-multi.json", 3), null,
                p -> {
                    throw new AssertionError("posters must not be called");
                }, null, List.of());

        ContentItem animatrix = item.orElseThrow();
        assertThat(animatrix.title()).isEqualTo("The Animatrix");
        assertThat(animatrix.subtitle()).isEqualTo("Movie");
        assertThat(animatrix.artwork()).isNull();
    }

    @Test
    void skipsPeopleAndAdultTitles() {
        assertThat(TmdbItemMapper.toItem(result("search-multi.json", 1), null, posters(), null, List.of())).isEmpty();
        assertThat(TmdbItemMapper.toItem(result("search-multi.json", 4), null, posters(), null, List.of())).isEmpty();
    }

    @Test
    void usesThePrefixAndThePlayables() {
        List<PlayableRef> playables = List.of(new PlayableRef.AppLink(URI.create("https://www.netflix.com/browse"), "netflix"));

        Optional<ContentItem> item = TmdbItemMapper.toItem(result("trending-all-week.json", 0), null, posters(),
                "On Netflix", playables);

        ContentItem strangerThings = item.orElseThrow();
        assertThat(strangerThings.subtitle()).isEqualTo("On Netflix · 2016");
        assertThat(strangerThings.playables()).isEqualTo(playables);
    }
}

package dev.andre.homecontrol.sources.tmdb;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class TmdbMediaRefTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void parsesItemIds() {
        assertThat(TmdbMediaRef.parse("movie-603")).contains(new TmdbMediaRef(TmdbMediaRef.Type.MOVIE, 603));
        assertThat(TmdbMediaRef.parse("movie-603").orElseThrow().path()).isEqualTo("/movie/603");
        assertThat(TmdbMediaRef.parse("tv-66732")).contains(new TmdbMediaRef(TmdbMediaRef.Type.TV, 66732));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"person-1", "movie-", "movie-0", "movie-12345678901", "movie-6a", "../movie-1"})
    void rejectsOtherIds(String itemId) {
        assertThat(TmdbMediaRef.parse(itemId)).isEmpty();
    }

    @Test
    void readsSearchResults() {
        JsonNode tv = JSON.readTree("{\"media_type\":\"tv\",\"id\":1}");
        assertThat(TmdbMediaRef.of(tv, null)).contains(new TmdbMediaRef(TmdbMediaRef.Type.TV, 1));

        JsonNode withHint = JSON.readTree("{\"id\":2}");
        assertThat(TmdbMediaRef.of(withHint, "movie")).contains(new TmdbMediaRef(TmdbMediaRef.Type.MOVIE, 2));

        JsonNode person = JSON.readTree("{\"media_type\":\"person\",\"id\":3}");
        assertThat(TmdbMediaRef.of(person, null)).isEmpty();

        JsonNode stringId = JSON.readTree("{\"media_type\":\"movie\",\"id\":\"7\"}");
        assertThat(TmdbMediaRef.of(stringId, null)).isEmpty();
    }
}

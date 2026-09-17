package dev.andre.homecontrol.sources.tmdb;

import dev.andre.homecontrol.core.playback.ContentItem;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every fixture under {@code /fixtures/tmdb} pinned to the shape the earlier TMDB tasks (and
 * {@link FakeTmdbServer}, {@link TmdbItemMapper}, {@link WatchProvider}) rely on: valid JSON,
 * TMDB's own list/details/error envelopes, items {@link TmdbItemMapper} can map, watch-provider
 * regions and entries in the documented shape, a secure image configuration, and — since these
 * fixtures ship in the repository and could be grepped by anyone — no real credential.
 */
class TmdbFixtureContractTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final Pattern ITEM_ID = Pattern.compile("^(movie|tv)-[1-9][0-9]*$");
    private static final Pattern REGION = Pattern.compile("^[A-Z]{2}$");
    private static final List<String> PROVIDER_CATEGORIES = List.of("flatrate", "free", "ads", "rent", "buy");

    private static Path fixturesDir() throws Exception {
        return Path.of(TmdbFixtureContractTest.class.getResource("/fixtures/tmdb").toURI());
    }

    private static List<String> fixtureNames() throws Exception {
        try (Stream<Path> paths = Files.list(fixturesDir())) {
            return paths.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    private static List<String> jsonFixtureNames() throws Exception {
        return fixtureNames().stream().filter(name -> name.endsWith(".json")).toList();
    }

    private static JsonNode fixture(String name) {
        return MAPPER.readTree(FakeTmdbServer.fixture(name));
    }

    @Test
    void everyFixtureIsAJsonObject() throws Exception {
        List<String> names = jsonFixtureNames();
        assertThat(names.size()).as("at least 13 TMDB fixtures").isGreaterThanOrEqualTo(13);
        for (String name : names) {
            assertThat(fixture(name).isObject()).as("fixture " + name + " parses to a JSON object").isTrue();
        }
    }

    @Test
    void listFixturesHaveTheDocumentedPagingShape() {
        for (String name : List.of("search-multi.json", "trending-all-week.json")) {
            JsonNode node = fixture(name);
            assertThat(node.path("page").isIntegralNumber()).as(name + " page").isTrue();
            assertThat(node.path("total_pages").isIntegralNumber()).as(name + " total_pages").isTrue();
            assertThat(node.path("total_results").isIntegralNumber()).as(name + " total_results").isTrue();
            assertThat(node.path("results").isArray()).as(name + " results").isTrue();
            for (JsonNode result : node.path("results")) {
                assertThat(result.path("id").isIntegralNumber()).as(name + " result id").isTrue();
                String mediaType = result.path("media_type").asString("");
                assertThat(mediaType).as(name + " media_type").isIn("movie", "tv", "person");
                if (mediaType.equals("movie")) {
                    assertThat(hasText(result, "title") || hasText(result, "original_title"))
                            .as(name + " movie " + result.path("id").asLong(0) + " has a title").isTrue();
                } else if (mediaType.equals("tv")) {
                    assertThat(hasText(result, "name") || hasText(result, "original_name"))
                            .as(name + " series " + result.path("id").asLong(0) + " has a name").isTrue();
                }
            }
        }
    }

    @Test
    void listFixturesProduceWellFormedItems() {
        var posters = (java.util.function.Function<String, URI>) p -> URI.create("https://image.tmdb.org/t/p/w342" + p);
        int mapped = 0;
        for (String name : List.of("search-multi.json", "trending-all-week.json")) {
            for (JsonNode result : fixture(name).path("results")) {
                Optional<ContentItem> item = TmdbItemMapper.toItem(result, null, posters, null, List.of());
                if (item.isEmpty()) {
                    continue;
                }
                mapped++;
                ContentItem content = item.orElseThrow();
                assertThat(content.id()).as(name + " item id").matches(ITEM_ID);
                assertThat(content.sourceId()).isEqualTo("tmdb");
                assertThat(content.title()).as(name + " item title").isNotBlank();
                assertThat(content.subtitle()).as(name + " item subtitle").satisfiesAnyOf(
                        s -> assertThat(s).startsWith("Movie"), s -> assertThat(s).startsWith("Series"));
                if (content.artwork() != null) {
                    assertThat(content.artwork().toString()).startsWith("https://image.tmdb.org/t/p/w342/");
                }
                assertThat(content.playables()).as(name + " item playables").isEmpty();
            }
        }
        assertThat(mapped).as("at least one mapped item across the list fixtures").isPositive();
    }

    @Test
    void providerFixturesHaveTheDocumentedShape() throws Exception {
        List<String> names = new ArrayList<>(jsonFixtureNames().stream().filter(name -> name.startsWith("providers-")).toList());
        assertThat(names).isNotEmpty();
        for (String name : names) {
            assertProviderShape(name, fixture(name));
        }
        for (String name : List.of("details-movie-603.json", "details-tv-66732.json")) {
            assertProviderShape(name, fixture(name).path("watch/providers"));
        }
    }

    private static void assertProviderShape(String name, JsonNode providers) {
        JsonNode results = providers.path("results");
        assertThat(results.isObject()).as(name + " results is an object").isTrue();
        for (var field : results.properties()) {
            String region = field.getKey();
            assertThat(region).as(name + " region key").matches(REGION);
            JsonNode forRegion = field.getValue();
            assertThat(forRegion.path("link").asString("")).as(name + " " + region + " link")
                    .startsWith("https://www.themoviedb.org/");
            for (String category : PROVIDER_CATEGORIES) {
                for (JsonNode entry : forRegion.path(category)) {
                    assertThat(entry.path("provider_id").isIntegralNumber()).as(name + " " + region + " " + category + " provider_id").isTrue();
                    assertThat(entry.path("provider_id").asInt(0)).as(name + " " + region + " " + category + " provider_id > 0").isPositive();
                    assertThat(entry.path("provider_name").asString("")).as(name + " " + region + " " + category + " provider_name").isNotBlank();
                    assertThat(entry.path("logo_path").asString("")).as(name + " " + region + " " + category + " logo_path").startsWith("/");
                    assertThat(entry.path("display_priority").isIntegralNumber()).as(name + " " + region + " " + category + " display_priority").isTrue();
                }
            }
        }
    }

    @Test
    void configurationFixtureHasSecureImages() {
        JsonNode images = fixture("configuration.json").path("images");
        assertThat(images.path("secure_base_url").asString("")).startsWith("https://");
        List<String> posterSizes = new ArrayList<>();
        images.path("poster_sizes").forEach(n -> posterSizes.add(n.asString("")));
        assertThat(posterSizes).contains("w342");
    }

    @Test
    void errorFixturesHaveStatusCodes() {
        for (String name : List.of("authentication-invalid.json", "not-found.json")) {
            JsonNode node = fixture(name);
            assertThat(node.path("status_code").isIntegralNumber()).as(name + " status_code").isTrue();
            assertThat(node.path("success").asBoolean(true)).as(name + " success").isFalse();
        }
    }

    @Test
    void noFixtureContainsACredential() throws Exception {
        for (String name : jsonFixtureNames()) {
            String text = FakeTmdbServer.fixture(name);
            assertThat(text).as(name).doesNotContain(FakeTmdbServer.READ_TOKEN).doesNotContain(FakeTmdbServer.API_KEY);
        }
    }

    private static boolean hasText(JsonNode node, String field) {
        return node.path(field).isString() && !node.path(field).asString("").isBlank();
    }
}

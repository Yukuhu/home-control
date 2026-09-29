package dev.andre.homecontrol.storage;

import dev.andre.homecontrol.core.content.SourcePreferences;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonFileSourceSettingsTest {

    private static final Path VERSION_ONE = Path.of("src/test/resources/fixtures/sources/sources-v1.json");

    private final JsonMapper mapper = JsonMapper.builder().build();

    @TempDir
    Path dir;

    /** A section as a source would keep it: a record, converted from version 1's flat strings by its own code. */
    record Probe(String serverUrl, Map<String, String> links) {
        static Optional<Probe> fromVersionOne(Map<String, String> flat) {
            if (flat.get("serverUrl") == null) {
                return Optional.empty();
            }
            Map<String, String> links = new TreeMap<>();
            flat.forEach((key, value) -> {
                if (key.startsWith("link.")) {
                    links.put(key.substring("link.".length()), value);
                }
            });
            return Optional.of(new Probe(flat.get("serverUrl"), links));
        }
    }

    private static Optional<Probe> probe(JsonFileSourceSettings settings, String id) {
        return settings.get(id, Probe.class, Probe::fromVersionOne);
    }

    @Test
    void anAbsentFileHasNoSettings() {
        Path file = dir.resolve("sources.json");
        JsonFileSourceSettings settings = new JsonFileSourceSettings(file);

        assertThat(probe(settings, "jellyfin")).isEmpty();
        assertThat(Files.exists(file)).isFalse();
    }

    @Test
    void aSectionIsStoredAsItsOwnJsonAndReadBackTyped() throws IOException {
        Path file = dir.resolve("sources.json");
        JsonFileSourceSettings settings = new JsonFileSourceSettings(file);

        settings.put("jellyfin", new Probe("http://nas:8096", Map.of("shield-1", "jf-9")));

        assertThat(probe(new JsonFileSourceSettings(file), "jellyfin"))
                .contains(new Probe("http://nas:8096", Map.of("shield-1", "jf-9")));
        JsonNode root = mapper.readTree(Files.readAllBytes(file));
        assertThat(root.path("version").asInt()).isEqualTo(2);
        assertThat(root.path("sources").path("jellyfin").path("links").path("shield-1").asString()).isEqualTo("jf-9");
    }

    @Test
    void removeDropsOnlyThatSource() {
        JsonFileSourceSettings settings = new JsonFileSourceSettings(dir.resolve("sources.json"));
        settings.put("jellyfin", new Probe("a", Map.of()));
        settings.put("other", new Probe("b", Map.of()));

        settings.remove("jellyfin");

        assertThat(probe(settings, "jellyfin")).isEmpty();
        assertThat(probe(settings, "other")).contains(new Probe("b", Map.of()));
    }

    @Test
    void aMalformedFileIsANamedStorageException() throws IOException {
        Path file = dir.resolve("sources.json");
        Files.writeString(file, "not json");
        JsonFileSourceSettings settings = new JsonFileSourceSettings(file);

        assertThatThrownBy(() -> probe(settings, "jellyfin"))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining(file.toString());
    }

    @Test
    void aVersionOneSectionIsConvertedByItsSourceOnFirstReadAndStoredTyped() throws IOException {
        Path file = dir.resolve("sources.json");
        Files.copy(VERSION_ONE, file);

        JsonFileSourceSettings settings = new JsonFileSourceSettings(file);
        assertThat(probe(settings, "jellyfin")).contains(new Probe("http://nas:8096", Map.of("shield-1", "jf-dev-9")));

        JsonNode root = mapper.readTree(Files.readAllBytes(file));
        assertThat(root.path("sources").path("jellyfin").path("serverUrl").asString()).isEqualTo("http://nas:8096");
        assertThat(root.path("unmigrated").has("jellyfin")).isFalse();
        assertThat(root.path("unmigrated").path("future-source").path("a").asString()).isEqualTo("1");
        assertThat(file.resolveSibling("sources.v1.json")).hasSameBinaryContentAs(VERSION_ONE);
        assertThat(settings.preferences()).get().extracting(SourcePreferences::locale).isEqualTo("de-DE");
    }

    @Test
    void anUnconvertedSectionSurvivesUntilItsSourceReadsIt() throws IOException {
        Path file = dir.resolve("sources.json");
        Files.copy(VERSION_ONE, file);
        JsonFileSourceSettings settings = new JsonFileSourceSettings(file);

        settings.put("jellyfin", new Probe("http://other:8096", Map.of()));
        settings.putPreferences(SourcePreferences.defaults("en-GB", "GB"));

        JsonNode root = mapper.readTree(Files.readAllBytes(file));
        assertThat(root.path("unmigrated").path("future-source").path("a").asString()).isEqualTo("1");
        assertThat(root.path("unmigrated").has("jellyfin")).isFalse();
        assertThat(probe(new JsonFileSourceSettings(file), "jellyfin")).contains(new Probe("http://other:8096", Map.of()));
    }

    @Test
    void anIncompleteVersionOneSectionReadsAsNotConnected() throws IOException {
        Path file = dir.resolve("sources.json");
        Files.writeString(file, "{\"version\":1,\"sources\":{\"jellyfin\":{\"userId\":\"u1\"}}}");

        assertThat(probe(new JsonFileSourceSettings(file), "jellyfin")).isEmpty();
        assertThat(probe(new JsonFileSourceSettings(file), "jellyfin")).isEmpty();
    }

    @Test
    void aSectionThatDoesNotBindIsANamedStorageException() throws IOException {
        Path file = dir.resolve("sources.json");
        Files.writeString(file, "{\"version\":2,\"sources\":{\"jellyfin\":{\"links\":\"not an object\"}}}");

        JsonFileSourceSettings settings = new JsonFileSourceSettings(file);

        assertThatThrownBy(() -> probe(settings, "jellyfin"))
                .isInstanceOf(StorageException.class)
                .hasMessage("Could not read the jellyfin settings in " + file + "; fix or delete that section");
    }

    @Test
    void resetDeletesTheFile() {
        Path file = dir.resolve("sources.json");
        JsonFileSourceSettings settings = new JsonFileSourceSettings(file);
        settings.put("jellyfin", new Probe("a", Map.of()));

        settings.reset();

        assertThat(file).doesNotExist();
        assertThat(probe(settings, "jellyfin")).isEmpty();
    }

    @Test
    void preferencesRoundTripBesideSourceSettings() throws IOException {
        Path file = dir.resolve("sources.json");
        JsonFileSourceSettings settings = new JsonFileSourceSettings(file);
        SourcePreferences preferences = new SourcePreferences(
                List.of("jellyfin/next-up", "jellyfin/resume"), Set.of("jellyfin/latest"), Set.of(),
                Map.of("jellyfin", 10), "de-DE", "DE", List.of("netflix"));

        settings.put("jellyfin", new Probe("http://nas:8096", Map.of()));
        settings.putPreferences(preferences);

        JsonFileSourceSettings reopened = new JsonFileSourceSettings(file);
        assertThat(probe(reopened, "jellyfin")).contains(new Probe("http://nas:8096", Map.of()));
        assertThat(reopened.preferences()).contains(preferences);

        JsonNode root = mapper.readTree(Files.readAllBytes(file));
        assertThat(root.path("preferences").path("railOrder").isArray()).isTrue();
        assertThat(root.path("preferences").path("railOrder").path(0).asString()).isEqualTo("jellyfin/next-up");
    }

    @Test
    void puttingSourceSettingsKeepsPreferences() {
        JsonFileSourceSettings settings = new JsonFileSourceSettings(dir.resolve("sources.json"));
        SourcePreferences preferences = SourcePreferences.defaults("de-DE", "DE").withSourceEnabled("jellyfin", false);
        settings.putPreferences(preferences);

        settings.put("jellyfin", new Probe("http://nas:8096", Map.of()));

        assertThat(settings.preferences()).contains(preferences);
    }

    @Test
    void removingASourceKeepsPreferences() {
        JsonFileSourceSettings settings = new JsonFileSourceSettings(dir.resolve("sources.json"));
        settings.put("jellyfin", new Probe("http://nas:8096", Map.of()));
        SourcePreferences preferences = SourcePreferences.defaults("de-DE", "DE").withSourceEnabled("jellyfin", false);
        settings.putPreferences(preferences);

        settings.remove("jellyfin");

        assertThat(settings.preferences()).contains(preferences);
    }

    @Test
    void aFileWithoutPreferencesHasNone() {
        JsonFileSourceSettings settings = new JsonFileSourceSettings(dir.resolve("sources.json"));
        settings.put("jellyfin", new Probe("http://nas:8096", Map.of()));

        assertThat(settings.preferences()).isEmpty();
    }

    @Test
    void malformedPreferencesAreANamedStorageException() throws IOException {
        Path file = dir.resolve("sources.json");
        Files.writeString(file, """
                {"version":1,"sources":{},"preferences":{"refreshMinutes":{"jellyfin":"often"}}}""");
        JsonFileSourceSettings settings = new JsonFileSourceSettings(file);

        assertThatThrownBy(settings::preferences)
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("preferences");
    }
}

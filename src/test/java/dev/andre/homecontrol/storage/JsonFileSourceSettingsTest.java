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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonFileSourceSettingsTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @TempDir
    Path dir;

    @Test
    void anAbsentFileHasNoSettings() {
        Path file = dir.resolve("sources.json");
        JsonFileSourceSettings settings = new JsonFileSourceSettings(file);

        assertThat(settings.get("jellyfin")).isEmpty();
        assertThat(Files.exists(file)).isFalse();
    }

    @Test
    void putWritesAtomicallyAndRoundTrips() throws IOException {
        Path file = dir.resolve("sources.json");
        JsonFileSourceSettings settings = new JsonFileSourceSettings(file);

        settings.put("jellyfin", Map.of("serverUrl", "http://nas:8096", "userId", "u1"));

        assertThat(settings.get("jellyfin")).containsEntry("serverUrl", "http://nas:8096").containsEntry("userId", "u1");

        JsonNode root = mapper.readTree(Files.readAllBytes(file));
        assertThat(root.path("version").asInt()).isEqualTo(1);
        assertThat(root.path("sources").path("jellyfin").path("serverUrl").asString()).isEqualTo("http://nas:8096");
    }

    @Test
    void removeDropsOnlyThatSource() {
        Path file = dir.resolve("sources.json");
        JsonFileSourceSettings settings = new JsonFileSourceSettings(file);
        settings.put("jellyfin", Map.of("a", "1"));
        settings.put("other", Map.of("b", "2"));

        settings.remove("jellyfin");

        assertThat(settings.get("jellyfin")).isEmpty();
        assertThat(settings.get("other")).containsEntry("b", "2");
    }

    @Test
    void aMalformedFileIsANamedStorageException() throws IOException {
        Path file = dir.resolve("sources.json");
        Files.writeString(file, "not json");
        JsonFileSourceSettings settings = new JsonFileSourceSettings(file);

        assertThatThrownBy(() -> settings.get("jellyfin"))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining(file.toString());
    }

    @Test
    void preferencesRoundTripBesideSourceSettings() throws IOException {
        Path file = dir.resolve("sources.json");
        JsonFileSourceSettings settings = new JsonFileSourceSettings(file);
        SourcePreferences preferences = new SourcePreferences(
                List.of("jellyfin/next-up", "jellyfin/resume"), Set.of("jellyfin/latest"), Set.of(),
                Map.of("jellyfin", 10), "de-DE", "DE", List.of("netflix"));

        settings.put("jellyfin", Map.of("serverUrl", "http://nas:8096"));
        settings.putPreferences(preferences);

        JsonFileSourceSettings reopened = new JsonFileSourceSettings(file);
        assertThat(reopened.get("jellyfin")).containsEntry("serverUrl", "http://nas:8096");
        assertThat(reopened.preferences()).contains(preferences);

        JsonNode root = mapper.readTree(Files.readAllBytes(file));
        assertThat(root.path("preferences").path("railOrder").isArray()).isTrue();
        assertThat(root.path("preferences").path("railOrder").path(0).asString()).isEqualTo("jellyfin/next-up");
    }

    @Test
    void puttingSourceSettingsKeepsPreferences() {
        Path file = dir.resolve("sources.json");
        JsonFileSourceSettings settings = new JsonFileSourceSettings(file);
        SourcePreferences preferences = SourcePreferences.defaults("de-DE", "DE").withSourceEnabled("jellyfin", false);
        settings.putPreferences(preferences);

        settings.put("jellyfin", Map.of("serverUrl", "http://nas:8096"));

        assertThat(settings.preferences()).contains(preferences);
    }

    @Test
    void removingASourceKeepsPreferences() {
        Path file = dir.resolve("sources.json");
        JsonFileSourceSettings settings = new JsonFileSourceSettings(file);
        settings.put("jellyfin", Map.of("serverUrl", "http://nas:8096"));
        SourcePreferences preferences = SourcePreferences.defaults("de-DE", "DE").withSourceEnabled("jellyfin", false);
        settings.putPreferences(preferences);

        settings.remove("jellyfin");

        assertThat(settings.preferences()).contains(preferences);
    }

    @Test
    void aFileWithoutPreferencesHasNone() {
        Path file = dir.resolve("sources.json");
        JsonFileSourceSettings settings = new JsonFileSourceSettings(file);
        settings.put("jellyfin", Map.of("serverUrl", "http://nas:8096"));

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

package dev.andre.homecontrol.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

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
}

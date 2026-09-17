package dev.andre.homecontrol.content;

import dev.andre.homecontrol.core.content.SourcePreferences;
import dev.andre.homecontrol.storage.JsonFileSourceSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SourcePreferencesServiceTest {

    @TempDir
    Path dir;

    final List<Object> published = new ArrayList<>();
    final ApplicationEventPublisher events = published::add;
    final ContentProperties properties = new ContentProperties(
            new ContentProperties.Rails(false, java.time.Duration.ofSeconds(15), java.time.Duration.ofMinutes(1), 4, Map.of()),
            "de-DE", "DE");

    private SourcePreferencesService service(Path file) {
        return new SourcePreferencesService(new JsonFileSourceSettings(file), properties, events);
    }

    @Test
    void currentUsesPropertyDefaultsWhenNothingIsStored() {
        SourcePreferencesService service = service(dir.resolve("sources.json"));

        SourcePreferences current = service.current();

        assertThat(current.locale()).isEqualTo("de-DE");
        assertThat(current.region()).isEqualTo("DE");
        assertThat(current.railOrder()).isEmpty();
    }

    @Test
    void updateWritesAndPublishes() {
        Path file = dir.resolve("sources.json");
        SourcePreferencesService service = service(file);

        SourcePreferences updated = service.update(prefs -> prefs.withSourceEnabled("jellyfin", false));

        assertThat(updated.disabledSources()).containsExactly("jellyfin");
        assertThat(published).hasSize(1).allSatisfy(e -> assertThat(e).isInstanceOf(SourcePreferencesChangedEvent.class));
        assertThat(new JsonFileSourceSettings(file).preferences()).contains(updated);
    }

    @Test
    void anInvalidUpdateWritesNothing() {
        Path file = dir.resolve("sources.json");
        SourcePreferencesService service = service(file);

        assertThatThrownBy(() -> service.update(prefs -> prefs.withRefreshMinutes("jellyfin", 0)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(new JsonFileSourceSettings(file).preferences()).isEmpty();
        assertThat(published).isEmpty();
    }
}

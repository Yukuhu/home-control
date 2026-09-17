package dev.andre.homecontrol.content;

import dev.andre.homecontrol.core.content.ContentSource;
import dev.andre.homecontrol.core.content.Rail;
import dev.andre.homecontrol.core.content.RailDescriptor;
import dev.andre.homecontrol.core.playback.ContentItem;
import dev.andre.homecontrol.storage.JsonFileSourceSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class StoredRailPreferencesTest {

    static final class StubSource implements ContentSource {
        final String id;
        final List<RailDescriptor> allRails;
        final Duration defaultInterval;
        boolean available = true;

        StubSource(String id, List<RailDescriptor> allRails, Duration defaultInterval) {
            this.id = id;
            this.allRails = allRails;
            this.defaultInterval = defaultInterval;
        }

        @Override public String id() { return id; }
        @Override public String displayName() { return id; }
        @Override public boolean available() { return available; }
        @Override public List<RailDescriptor> rails() { return available ? allRails : List.of(); }
        @Override public Rail rail(String railId) { throw new UnsupportedOperationException(); }
        @Override public Optional<ContentItem> item(String itemId) { return Optional.empty(); }
        @Override public Duration defaultRefreshInterval() { return defaultInterval; }
    }

    @TempDir
    Path dir;

    static final RailDescriptor RESUME = new RailDescriptor("jellyfin", "resume", "Continue watching");
    static final RailDescriptor NEXT_UP = new RailDescriptor("jellyfin", "next-up", "Next up");
    static final RailDescriptor LATEST = new RailDescriptor("jellyfin", "latest", "Latest in library");
    static final RailDescriptor SUBS = new RailDescriptor("tube", "subs", "Subscriptions");

    final StubSource jellyfin = new StubSource("jellyfin", List.of(RESUME, NEXT_UP, LATEST), Duration.ofMinutes(15));
    final StubSource tube = new StubSource("tube", List.of(SUBS), Duration.ofMinutes(60));
    final List<ContentSource> sources = List.of(jellyfin, tube);

    final ContentProperties properties = new ContentProperties(
            new ContentProperties.Rails(false, Duration.ofSeconds(15), Duration.ofMinutes(1), 4, Map.of()),
            "de-DE", "DE");

    private SourcePreferencesService service(Path file, ContentProperties props) {
        return new SourcePreferencesService(new JsonFileSourceSettings(file), props, event -> { });
    }

    private StoredRailPreferences preferences() {
        return new StoredRailPreferences(service(dir.resolve("sources.json"), properties), properties);
    }

    @Test
    void withoutPreferencesRailsFollowSourceOrder() {
        StoredRailPreferences prefs = preferences();

        assertThat(prefs.allRailsInOrder(sources)).containsExactly(RESUME, NEXT_UP, LATEST, SUBS);
        assertThat(prefs.rails(sources)).containsExactly(RESUME, NEXT_UP, LATEST, SUBS);
    }

    @Test
    void storedOrderWinsAndNewRailsAppend() {
        SourcePreferencesService service = service(dir.resolve("sources.json"), properties);
        service.update(p -> p.withRailOrder(List.of("tube/subs", "jellyfin/latest")));
        StoredRailPreferences prefs = new StoredRailPreferences(service, properties);

        assertThat(prefs.allRailsInOrder(sources)).containsExactly(SUBS, LATEST, RESUME, NEXT_UP);
    }

    @Test
    void hiddenRailsAreNotShownButStayInTheSetupList() {
        SourcePreferencesService service = service(dir.resolve("sources.json"), properties);
        service.update(p -> p.withRailVisible("jellyfin/latest", false));
        StoredRailPreferences prefs = new StoredRailPreferences(service, properties);

        assertThat(prefs.rails(sources)).doesNotContain(LATEST);
        assertThat(prefs.allRailsInOrder(sources)).contains(LATEST);
    }

    @Test
    void disabledSourcesDisappearAndAreNotSearchable() {
        SourcePreferencesService service = service(dir.resolve("sources.json"), properties);
        service.update(p -> p.withSourceEnabled("tube", false));
        StoredRailPreferences prefs = new StoredRailPreferences(service, properties);

        assertThat(prefs.rails(sources)).doesNotContain(SUBS);
        assertThat(prefs.allRailsInOrder(sources)).doesNotContain(SUBS);
        assertThat(prefs.sourceEnabled("tube")).isFalse();
        assertThat(prefs.sourceEnabled("jellyfin")).isTrue();
    }

    @Test
    void unavailableSourcesKeepTheirStoredPlace() {
        SourcePreferencesService service = service(dir.resolve("sources.json"), properties);
        service.update(p -> p.withRailOrder(List.of("tube/subs", "jellyfin/latest")));
        StoredRailPreferences prefs = new StoredRailPreferences(service, properties);
        assertThat(prefs.allRailsInOrder(sources)).containsExactly(SUBS, LATEST, RESUME, NEXT_UP);

        tube.available = false;
        assertThat(prefs.allRailsInOrder(sources)).containsExactly(LATEST, RESUME, NEXT_UP);

        tube.available = true;
        assertThat(prefs.allRailsInOrder(sources)).containsExactly(SUBS, LATEST, RESUME, NEXT_UP);
    }

    @Test
    void intervalPrecedence() {
        SourcePreferencesService service = service(dir.resolve("sources.json"), properties);
        service.update(p -> p.withRefreshMinutes("tube", 10));
        StoredRailPreferences prefs = new StoredRailPreferences(service, properties);
        assertThat(prefs.refreshInterval(tube)).isEqualTo(Duration.ofMinutes(10));

        ContentProperties withPropertyOverride = new ContentProperties(
                new ContentProperties.Rails(false, Duration.ofSeconds(15), Duration.ofMinutes(1), 4,
                        Map.of("tube", Duration.ofMinutes(2))),
                "de-DE", "DE");
        StoredRailPreferences withoutStored = new StoredRailPreferences(
                service(dir.resolve("other.json"), withPropertyOverride), withPropertyOverride);
        assertThat(withoutStored.refreshInterval(tube)).isEqualTo(Duration.ofMinutes(2));

        StoredRailPreferences plainDefault = new StoredRailPreferences(
                service(dir.resolve("plain.json"), properties), properties);
        assertThat(plainDefault.refreshInterval(tube)).isEqualTo(Duration.ofMinutes(60));
    }
}

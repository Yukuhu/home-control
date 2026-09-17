package dev.andre.homecontrol.content;

import dev.andre.homecontrol.core.content.ContentSource;
import dev.andre.homecontrol.core.content.Rail;
import dev.andre.homecontrol.core.content.RailDescriptor;
import dev.andre.homecontrol.core.playback.ContentItem;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultRailPreferencesTest {

    private static ContentSource source(String id, boolean available, RailDescriptor... rails) {
        return new ContentSource() {
            @Override public String id() { return id; }
            @Override public String displayName() { return id; }
            @Override public boolean available() { return available; }
            @Override public List<RailDescriptor> rails() { return List.of(rails); }
            @Override public Rail rail(String railId) { throw new UnsupportedOperationException(); }
            @Override public Optional<ContentItem> item(String itemId) { return Optional.empty(); }
        };
    }

    @Test
    void listsRailsOfAvailableSourcesInSourceOrder() {
        RailDescriptor firstRail = new RailDescriptor("first", "a", "A");
        RailDescriptor secondRail = new RailDescriptor("second", "a", "A");
        ContentSource first = source("first", true, firstRail);
        ContentSource second = source("second", false, secondRail);
        DefaultRailPreferences preferences = new DefaultRailPreferences(
                new ContentProperties(new ContentProperties.Rails(true, Duration.ofSeconds(15), Duration.ofMinutes(1), 4, Map.of())));

        List<RailDescriptor> rails = preferences.rails(List.of(first, second));

        assertThat(rails).containsExactly(firstRail);
    }

    @Test
    void usesThePropertyOverrideElseTheSourceDefault() {
        ContentSource stub = new ContentSource() {
            @Override public String id() { return "stub"; }
            @Override public String displayName() { return "Stub"; }
            @Override public List<RailDescriptor> rails() { return List.of(); }
            @Override public Rail rail(String railId) { throw new UnsupportedOperationException(); }
            @Override public Optional<ContentItem> item(String itemId) { return Optional.empty(); }
            @Override public Duration defaultRefreshInterval() { return Duration.ofMinutes(15); }
        };
        ContentSource other = new ContentSource() {
            @Override public String id() { return "other"; }
            @Override public String displayName() { return "Other"; }
            @Override public List<RailDescriptor> rails() { return List.of(); }
            @Override public Rail rail(String railId) { throw new UnsupportedOperationException(); }
            @Override public Optional<ContentItem> item(String itemId) { return Optional.empty(); }
            @Override public Duration defaultRefreshInterval() { return Duration.ofMinutes(20); }
        };
        DefaultRailPreferences preferences = new DefaultRailPreferences(new ContentProperties(
                new ContentProperties.Rails(true, Duration.ofSeconds(15), Duration.ofMinutes(1), 4,
                        Map.of("stub", Duration.ofMinutes(2)))));

        assertThat(preferences.refreshInterval(stub)).isEqualTo(Duration.ofMinutes(2));
        assertThat(preferences.refreshInterval(other)).isEqualTo(Duration.ofMinutes(20));
    }

    @Test
    void everySourceIsEnabled() {
        DefaultRailPreferences preferences = new DefaultRailPreferences(new ContentProperties(
                new ContentProperties.Rails(true, Duration.ofSeconds(15), Duration.ofMinutes(1), 4, Map.of())));

        assertThat(preferences.sourceEnabled("anything")).isTrue();
    }
}

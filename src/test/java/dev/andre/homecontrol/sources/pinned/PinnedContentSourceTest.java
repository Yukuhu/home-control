package dev.andre.homecontrol.sources.pinned;

import dev.andre.homecontrol.core.playback.ContentItem;
import dev.andre.homecontrol.core.playback.PlayableRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class PinnedContentSourceTest {

    @TempDir
    Path dir;

    PinnedShortcuts shortcuts;
    PinnedContentSource source;

    @BeforeEach
    void setUp() {
        JsonFilePinStore store = new JsonFilePinStore(dir.resolve("pinned.json"));
        shortcuts = new PinnedShortcuts(store, new PinnedProperties(true, 200), mock(org.springframework.context.ApplicationEventPublisher.class),
                Clock.fixed(Instant.parse("2026-09-16T10:00:00Z"), ZoneOffset.UTC), new SecureRandom());
        source = new PinnedContentSource(shortcuts);
    }

    @Test
    void isUnavailableWithoutPins() {
        assertThat(source.available()).isFalse();
        assertThat(source.rails()).isEmpty();
    }

    @Test
    void offersOnePinnedRailInPinOrder() {
        shortcuts.add("https://www.netflix.com/title/1", "Stranger Things");
        shortcuts.add("https://www.dazn.com/de-DE/home", "DAZN");

        assertThat(source.rails()).containsExactly(
                new dev.andre.homecontrol.core.content.RailDescriptor("pinned", "pinned", "Pinned"));

        var rail = source.rail("pinned");
        assertThat(rail.items()).extracting(ContentItem::title).containsExactly("Stranger Things", "DAZN");
        assertThat(rail.items()).allSatisfy(item -> {
            assertThat(item.sourceId()).isEqualTo("pinned");
            assertThat(item.playables()).singleElement().isInstanceOf(PlayableRef.AppLink.class);
        });

        assertThatThrownBy(() -> source.rail("x")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void readsAnItemById() {
        Pin pin = shortcuts.add("https://www.netflix.com/title/1", "Stranger Things");

        assertThat(source.item(pin.id())).isPresent();
        assertThat(source.item("p-ffffffffffff")).isEmpty();
    }

    @Test
    void searchesTitlesLocally() {
        shortcuts.add("https://www.netflix.com/title/1", "Stranger Things");
        shortcuts.add("https://www.netflix.com/title/2", "The Boys");

        assertThat(source.searchable()).isTrue();
        assertThat(source.search("str", 10)).extracting(ContentItem::title).containsExactly("Stranger Things");
    }

    @Test
    void refreshesDaily() {
        assertThat(source.defaultRefreshInterval()).isEqualTo(Duration.ofHours(24));
    }
}

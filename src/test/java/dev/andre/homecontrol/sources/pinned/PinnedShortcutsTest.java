package dev.andre.homecontrol.sources.pinned;

import dev.andre.homecontrol.core.content.ContentChangedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.context.ApplicationEventPublisher;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

class PinnedShortcutsTest {

    static final Instant NOW = Instant.parse("2026-09-16T10:00:00Z");

    @TempDir
    Path dir;

    JsonFilePinStore store;
    ApplicationEventPublisher events;
    PinnedShortcuts shortcuts;

    @BeforeEach
    void setUp() {
        store = new JsonFilePinStore(dir.resolve("pinned.json"));
        events = mock(ApplicationEventPublisher.class);
        shortcuts = new PinnedShortcuts(store, new PinnedProperties(true, 3), events,
                Clock.fixed(NOW, ZoneOffset.UTC), new SecureRandom());
    }

    @Test
    void addsALinkWithDetectedServiceAndDefaultTitle() {
        Pin pin = shortcuts.add("  https://www.netflix.com/title/80057281 ", "");

        assertThat(pin.id()).matches("^p-[0-9a-f]{12}$");
        assertThat(pin.service()).isEqualTo("netflix");
        assertThat(pin.title()).isEqualTo("Netflix link");
        assertThat(pin.subtitle()).isEqualTo("Netflix");
        assertThat(pin.kind().name()).isEqualTo("VIDEO");
        assertThat(pin.createdAt()).isEqualTo(NOW);
        assertThat(store.load()).containsExactly(pin);
        verify(events, times(1)).publishEvent(new ContentChangedEvent("pinned"));
    }

    @Test
    void usesTheGivenTitle() {
        Pin pin = shortcuts.add("https://www.netflix.com/title/1", " Stranger Things ");

        assertThat(pin.title()).isEqualTo("Stranger Things");
    }

    @Test
    void webLinksAreTitledByHost() {
        Pin pin = shortcuts.add("https://example.org/x", "");

        assertThat(pin.title()).isEqualTo("example.org");
        assertThat(pin.subtitle()).isEqualTo("example.org");
        assertThat(pin.service()).isEqualTo("web");
    }

    @ParameterizedTest
    @CsvSource(value = {
            "'', , Enter a link to pin",
            "ftp://x/y, , Only http and https links can be opened on a device",
    })
    void rejectsBadInput(String url, String title, String message) {
        assertThatThrownBy(() -> shortcuts.add(url, title))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(message);

        assertThat(store.load()).isEmpty();
        verifyNoMoreInteractions(events);
    }

    @Test
    void rejectsAnOverlongUrl() {
        String url = "https://example.org/" + "a".repeat(2049);

        assertThatThrownBy(() -> shortcuts.add(url, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("That link is too long to pin");
        assertThat(store.load()).isEmpty();
    }

    @Test
    void rejectsAnOverlongTitle() {
        assertThatThrownBy(() -> shortcuts.add("https://example.org/x", "a".repeat(121)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Keep the title under 120 characters");
        assertThat(store.load()).isEmpty();
    }

    @Test
    void refusesDuplicatesAndTooManyPins() {
        shortcuts.add("https://example.org/a", "A");

        assertThatThrownBy(() -> shortcuts.add("https://example.org/a", "Again"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("That link is already pinned");

        shortcuts.add("https://example.org/b", "B");
        shortcuts.add("https://example.org/c", "C");

        assertThatThrownBy(() -> shortcuts.add("https://example.org/d", "D"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("You can pin up to 3 links");
    }

    @Test
    void renamesMovesAndRemoves() {
        Pin a = shortcuts.add("https://example.org/a", "A");
        Pin b = shortcuts.add("https://example.org/b", "B");
        Pin c = shortcuts.add("https://example.org/c", "C");

        shortcuts.move(c.id(), true);
        assertThat(shortcuts.all()).extracting(Pin::id).containsExactly(a.id(), c.id(), b.id());

        shortcuts.move(a.id(), true);
        assertThat(shortcuts.all()).extracting(Pin::id).containsExactly(a.id(), c.id(), b.id());

        shortcuts.rename(b.id(), "Bee");
        assertThat(shortcuts.find(b.id()).orElseThrow().title()).isEqualTo("Bee");

        shortcuts.remove(a.id());
        assertThat(shortcuts.all()).extracting(Pin::id).containsExactly(c.id(), b.id());

        assertThatThrownBy(() -> shortcuts.rename("p-000000000000", "x"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("No pinned link p-000000000000");
        assertThatThrownBy(() -> shortcuts.move("p-000000000000", true))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("No pinned link p-000000000000");
        assertThatThrownBy(() -> shortcuts.remove("p-000000000000"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("No pinned link p-000000000000");
        assertThatThrownBy(() -> shortcuts.rename(b.id(), " "))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Enter a title");
    }

    @Test
    void survivesARestart() {
        shortcuts.add("https://example.org/a", "A");
        shortcuts.add("https://example.org/b", "B");

        PinnedShortcuts restarted = new PinnedShortcuts(store, new PinnedProperties(true, 3), events,
                Clock.fixed(NOW, ZoneOffset.UTC), new SecureRandom());

        assertThat(restarted.all()).extracting(Pin::title).containsExactly("A", "B");
    }
}

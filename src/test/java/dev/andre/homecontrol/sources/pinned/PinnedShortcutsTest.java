package dev.andre.homecontrol.sources.pinned;

import dev.andre.homecontrol.core.content.ContentChangedEvent;
import dev.andre.homecontrol.core.content.ContentSource;
import dev.andre.homecontrol.core.content.ContentSourceException;
import dev.andre.homecontrol.core.content.ContentSources;
import dev.andre.homecontrol.core.playback.ContentItem;
import dev.andre.homecontrol.core.playback.ContentKind;
import dev.andre.homecontrol.core.playback.PlayableRef;
import dev.andre.homecontrol.core.playback.ServiceLinks;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;

import java.net.URI;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
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
    ContentSources sources;
    ContentSource tmdbSource;
    PinnedShortcuts shortcuts;

    @SuppressWarnings("unchecked")
    private static ObjectProvider<ContentSources> providerOf(ContentSources sources) {
        ObjectProvider<ContentSources> provider = mock(ObjectProvider.class);
        given(provider.getIfAvailable()).willReturn(sources);
        return provider;
    }

    @BeforeEach
    void setUp() {
        store = new JsonFilePinStore(dir.resolve("pinned.json"));
        events = mock(ApplicationEventPublisher.class);
        sources = mock(ContentSources.class);
        tmdbSource = mock(ContentSource.class);
        given(sources.find("tmdb")).willReturn(Optional.of(tmdbSource));

        PlayableRef.AppLink netflixHome = new PlayableRef.AppLink(ServiceLinks.appHome("netflix").orElseThrow(), "netflix");
        ContentItem strangerThings = new ContentItem("tv-66732", "tmdb", ContentKind.VIDEO, "Stranger Things",
                "On Netflix · 2016", URI.create("https://image.tmdb.org/t/p/w342/49WJfeN0moxb9IPfGn8AIqMGskD.jpg"),
                List.of(netflixHome));
        given(tmdbSource.item("tv-66732")).willReturn(Optional.of(strangerThings));

        PlayableRef.AppLink netflixTitleLink = ServiceLinks.appLink(ServiceLinks.netflixTitle("603"));
        ContentItem alreadyDirect = new ContentItem("movie-9", "tmdb", ContentKind.MOVIE, "Matrix", "Movie · 1999",
                null, List.of(netflixTitleLink));
        given(tmdbSource.item("movie-9")).willReturn(Optional.of(alreadyDirect));

        shortcuts = new PinnedShortcuts(store, new PinnedProperties(true, 3), events,
                Clock.fixed(NOW, ZoneOffset.UTC), new SecureRandom(), providerOf(sources));
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
    void storesCanonicalLinksAndSpotsDuplicatesAcrossForms() {
        Pin pin = shortcuts.add("https://www.netflix.com/de/title/80057281?s=a", "");

        assertThat(pin.url()).isEqualTo(URI.create("https://www.netflix.com/title/80057281"));

        assertThatThrownBy(() -> shortcuts.add("https://www.netflix.com/watch/80057281", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("That link is already pinned");
    }

    @Test
    void survivesARestart() {
        shortcuts.add("https://example.org/a", "A");
        shortcuts.add("https://example.org/b", "B");

        PinnedShortcuts restarted = new PinnedShortcuts(store, new PinnedProperties(true, 3), events,
                Clock.fixed(NOW, ZoneOffset.UTC), new SecureRandom(), providerOf(sources));

        assertThat(restarted.all()).extracting(Pin::title).containsExactly("A", "B");
    }

    @Test
    void upgradesAnItemWithItsMetadata() {
        Pin pin = shortcuts.addUpgrade("https://www.netflix.com/de/title/80057281?s=a", "tmdb/tv-66732");

        assertThat(pin.title()).isEqualTo("Stranger Things");
        assertThat(pin.subtitle()).isEqualTo("Netflix");
        assertThat(pin.artwork()).isEqualTo(URI.create("https://image.tmdb.org/t/p/w342/49WJfeN0moxb9IPfGn8AIqMGskD.jpg"));
        assertThat(pin.kind()).isEqualTo(ContentKind.VIDEO);
        assertThat(pin.upgradeOf()).isEqualTo("tmdb/tv-66732");
        assertThat(pin.url()).isEqualTo(URI.create("https://www.netflix.com/title/80057281"));

        verify(events).publishEvent(new ContentChangedEvent("pinned"));
        verify(events).publishEvent(new ContentChangedEvent("tmdb"));

        assertThat(shortcuts.linkFor("tmdb", "tv-66732"))
                .contains(new PlayableRef.AppLink(URI.create("https://www.netflix.com/title/80057281"), "netflix"));
        assertThat(shortcuts.linkFor("tmdb", "tv-1")).isEmpty();
    }

    @Test
    void upgradingAgainReplacesThePin() {
        Pin first = shortcuts.addUpgrade("https://www.netflix.com/title/80057281", "tmdb/tv-66732");

        Pin second = shortcuts.addUpgrade("https://app.primevideo.com/detail?gti=amzn1.dv.gti.8eb3c4a1-1b2c-4d5e-9f60-718293a4b5c6",
                "tmdb/tv-66732");

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.url()).isEqualTo(URI.create("https://app.primevideo.com/detail?gti=amzn1.dv.gti.8eb3c4a1-1b2c-4d5e-9f60-718293a4b5c6"));
        assertThat(shortcuts.all()).hasSize(1);
    }

    @Test
    void refusesWhatCannotBeUpgraded() {
        assertThatThrownBy(() -> shortcuts.addUpgrade("https://example.org/x", "tmdb/../x"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("That item cannot be pinned");

        assertThatThrownBy(() -> shortcuts.addUpgrade("https://example.org/x", "nope/tv-1"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("That item is no longer available");

        given(tmdbSource.item("tv-404")).willReturn(Optional.empty());
        assertThatThrownBy(() -> shortcuts.addUpgrade("https://example.org/x", "tmdb/tv-404"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("That item is no longer available");

        assertThatThrownBy(() -> shortcuts.addUpgrade("https://example.org/x", "tmdb/movie-9"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("This item already opens directly");

        assertThatThrownBy(() -> shortcuts.addUpgrade("javascript:alert(1)", "tmdb/tv-66732"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Only http and https links can be opened on a device");

        assertThat(shortcuts.all()).isEmpty();
        verifyNoMoreInteractions(events);
    }

    @Test
    void refusesAnUpgradeWhenAtMaxPins() {
        shortcuts.add("https://example.org/a", "A");
        shortcuts.add("https://example.org/b", "B");
        shortcuts.add("https://example.org/c", "C");

        assertThatThrownBy(() -> shortcuts.addUpgrade("https://www.netflix.com/title/1", "tmdb/tv-66732"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("You can pin up to 3 links");
        assertThat(shortcuts.all()).hasSize(3);
    }

    @Test
    void refusesAnAppHomeLinkAsAnUpgrade() {
        assertThatThrownBy(() -> shortcuts.addUpgrade("https://www.netflix.com/browse", "tmdb/tv-66732"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Paste a link to this title, not the app's home screen");
        assertThat(shortcuts.all()).isEmpty();
    }

    @Test
    void refusesWhenTheSourceLookupFails() {
        given(tmdbSource.item("tv-500")).willThrow(new ContentSourceException("TMDB had a server error"));

        assertThatThrownBy(() -> shortcuts.addUpgrade("https://example.org/x", "tmdb/tv-500"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("TMDB had a server error");
    }

    @Test
    void artworkFromTheItemIsKeptOnlyWhenSafe() {
        given(tmdbSource.item("tv-77")).willReturn(Optional.of(new ContentItem("tv-77", "tmdb", ContentKind.VIDEO,
                "Insecure Art", null, URI.create("http://example.org/x.jpg"),
                List.of(new PlayableRef.AppLink(ServiceLinks.appHome("netflix").orElseThrow(), "netflix")))));

        Pin pin = shortcuts.addUpgrade("https://www.netflix.com/title/1", "tmdb/tv-77");

        assertThat(pin.artwork()).isNull();
    }
}

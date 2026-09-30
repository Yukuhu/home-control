package dev.andre.homecontrol.content;

import dev.andre.homecontrol.core.content.ContentSource;
import dev.andre.homecontrol.core.content.ContentSourceException;
import dev.andre.homecontrol.core.content.ContentSources;
import dev.andre.homecontrol.core.content.Rail;
import dev.andre.homecontrol.core.content.RailDescriptor;
import dev.andre.homecontrol.core.playback.ContentItem;
import dev.andre.homecontrol.core.playback.ContentKind;
import dev.andre.homecontrol.testsupport.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class RailCacheLifecycleTest {

    private static final Duration TICK = Duration.ofMillis(50);
    private static final Duration NO_TICKS = Duration.ofMillis(300);
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final MutableClock clock = MutableClock.at(Instant.parse("2026-09-16T09:00:00Z"));
    private final LifecycleSource source = new LifecycleSource();
    private final List<Object> events = new CopyOnWriteArrayList<>();
    private final ExecutorService fetches = Executors.newVirtualThreadPerTaskExecutor();
    private RailCache cache;

    @AfterEach
    void stop() {
        if (cache != null) {
            cache.stop();
        } else {
            fetches.shutdownNow();
        }
        if (source.fetchGate != null) {
            source.fetchGate.countDown();
        }
    }

    @Test
    void startingTheSchedulerLoadsRailsAndRefreshesThemWhenDue() {
        createCache(true);
        assertThat(cache.isRunning()).isFalse();

        cache.start();

        assertThat(cache.isRunning()).isTrue();
        await().atMost(TIMEOUT).untilAsserted(() -> {
            assertThat(cache.peek()).singleElement().satisfies(snapshot -> {
                assertThat(snapshot.status()).isEqualTo(RailStatus.READY);
                assertThat(snapshot.items()).extracting(ContentItem::id).containsExactly("item-1");
            });
        });

        clock.advance(Duration.ofMinutes(10));

        await().atMost(TIMEOUT).untilAsserted(() -> {
            assertThat(cache.peek()).singleElement().satisfies(snapshot -> {
                assertThat(snapshot.items()).extracting(ContentItem::id).containsExactly("item-2");
                assertThat(snapshot.fetchedAt()).isEqualTo(clock.instant());
            });
            assertThat(events).filteredOn(RailUpdatedEvent.class::isInstance)
                    .extracting(event -> ((RailUpdatedEvent) event).snapshot().status())
                    .containsExactly(RailStatus.LOADING, RailStatus.READY, RailStatus.READY, RailStatus.READY);
        });
    }

    @Test
    void aDisabledSchedulerStillAllowsPageReadsToLoadRails() {
        createCache(false);

        cache.start();

        assertThat(cache.isRunning()).isTrue();
        await().during(NO_TICKS).atMost(TIMEOUT).untilAsserted(() -> {
            assertThat(cache.peek()).isEmpty();
            assertThat(events).isEmpty();
        });

        cache.snapshots();

        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(cache.peek()).singleElement()
                        .satisfies(snapshot -> assertThat(snapshot.status()).isEqualTo(RailStatus.READY)));
    }

    @Test
    void aFailedTickDoesNotPreventTheNextTickFromLoadingRails() {
        source.failNextDiscovery.set(true);
        createCache(true);

        cache.start();

        await().atMost(TIMEOUT).untilAsserted(() -> {
            assertThat(cache.peek()).singleElement().satisfies(snapshot -> {
                assertThat(snapshot.status()).isEqualTo(RailStatus.READY);
                assertThat(snapshot.items()).extracting(ContentItem::id).containsExactly("item-1");
            });
            assertThat(events).filteredOn(RailUpdatedEvent.class::isInstance)
                    .extracting(event -> ((RailUpdatedEvent) event).snapshot().status())
                    .containsExactly(RailStatus.LOADING, RailStatus.READY);
        });
    }

    @Test
    void stoppingTheSchedulerPreventsLaterReconciliation() {
        createCache(true);
        cache.start();
        await().atMost(TIMEOUT).untilAsserted(() -> {
            assertThat(cache.peek()).singleElement()
                    .satisfies(snapshot -> assertThat(snapshot.status()).isEqualTo(RailStatus.READY));
            assertThat(events).filteredOn(RailUpdatedEvent.class::isInstance)
                    .extracting(event -> ((RailUpdatedEvent) event).snapshot().status())
                    .containsExactly(RailStatus.LOADING, RailStatus.READY);
        });

        cache.stop();
        // Let a tick already running when stop was called finish before changing the source.
        await().during(NO_TICKS).atMost(TIMEOUT).untilAsserted(() ->
                assertThat(cache.peek()).singleElement()
                        .satisfies(snapshot -> assertThat(snapshot.status()).isEqualTo(RailStatus.READY)));
        source.available = false;
        events.clear();

        assertThat(cache.isRunning()).isFalse();
        await().during(NO_TICKS).atMost(TIMEOUT).untilAsserted(() -> {
            assertThat(cache.peek()).singleElement()
                    .satisfies(snapshot -> assertThat(snapshot.status()).isEqualTo(RailStatus.READY));
            assertThat(events).isEmpty();
        });
    }

    @Test
    void stoppingInterruptsAnActiveFetch() {
        source.fetchGate = new CountDownLatch(1);
        createCache(true);
        cache.start();
        await().atMost(TIMEOUT).until(() -> source.fetchStarted.getCount() == 0);

        cache.stop();

        await().atMost(TIMEOUT).untilAsserted(() -> {
            assertThat(cache.peek()).singleElement().satisfies(snapshot -> {
                assertThat(snapshot.status()).isEqualTo(RailStatus.FAILED);
                assertThat(snapshot.refreshing()).isFalse();
                assertThat(snapshot.error()).isEqualTo("Fetch interrupted");
            });
            assertThat(events).filteredOn(RailUpdatedEvent.class::isInstance)
                    .extracting(event -> ((RailUpdatedEvent) event).snapshot().status())
                    .containsExactly(RailStatus.LOADING, RailStatus.FAILED);
        });
    }

    private void createCache(boolean schedulerEnabled) {
        ContentProperties properties = new ContentProperties(
                new ContentProperties.Rails(schedulerEnabled, TICK, Duration.ofMinutes(1), 1, Map.of()),
                new ContentProperties.Search(Duration.ofSeconds(8)), "de-DE", "DE");
        cache = new RailCache(new ContentSources(List.of(source)), new RailCacheTest.StubPreferences(),
                events::add, clock, properties, fetches);
    }

    private final class LifecycleSource implements ContentSource {
        private final AtomicInteger fetchCount = new AtomicInteger();
        private final AtomicBoolean failNextDiscovery = new AtomicBoolean();
        private final CountDownLatch fetchStarted = new CountDownLatch(1);
        private volatile CountDownLatch fetchGate;
        private volatile boolean available = true;

        @Override public String id() { return "lifecycle"; }
        @Override public String displayName() { return "Lifecycle"; }
        @Override public boolean available() { return available; }
        @Override public List<RailDescriptor> rails() {
            if (failNextDiscovery.getAndSet(false)) {
                throw new IllegalStateException("Discovery failed once");
            }
            return List.of(new RailDescriptor(id(), "a", "Rail A"));
        }
        @Override public Rail rail(String railId) {
            fetchStarted.countDown();
            if (fetchGate != null) {
                try {
                    fetchGate.await();
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                    throw new ContentSourceException(ContentSourceException.Kind.UNREACHABLE, "Fetch interrupted");
                }
            }
            return new Rail(new RailDescriptor(id(), railId, "Rail A"),
                    List.of(new ContentItem("item-" + fetchCount.incrementAndGet(), id(), ContentKind.MOVIE,
                            "Item", null, null, List.of())), clock.instant());
        }
        @Override public Optional<ContentItem> item(String itemId) { return Optional.empty(); }
        @Override public Duration defaultRefreshInterval() { return Duration.ofMinutes(10); }
    }
}

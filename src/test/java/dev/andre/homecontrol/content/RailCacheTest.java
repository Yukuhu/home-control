package dev.andre.homecontrol.content;

import dev.andre.homecontrol.core.content.ContentSource;
import dev.andre.homecontrol.core.content.ContentSourceException;
import dev.andre.homecontrol.core.content.ContentSources;
import dev.andre.homecontrol.core.content.Rail;
import dev.andre.homecontrol.core.content.RailDescriptor;
import dev.andre.homecontrol.core.content.ContentChangedEvent;
import dev.andre.homecontrol.core.playback.ContentItem;
import dev.andre.homecontrol.core.playback.ContentKind;
import dev.andre.homecontrol.testsupport.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class RailCacheTest {

    /** Runs tasks only when told to, so "in flight" is observable. */
    static final class ManualExecutor extends AbstractExecutorService {
        final List<Runnable> queued = new ArrayList<>();
        @Override public void execute(Runnable command) { queued.add(command); }
        void runAll() { List<Runnable> now = new ArrayList<>(queued); queued.clear(); now.forEach(Runnable::run); }
        @Override public void shutdown() { /* nothing runs in the background, so there is nothing to stop */ }
        @Override public List<Runnable> shutdownNow() { return List.of(); }
        @Override public boolean isShutdown() { return false; }
        @Override public boolean isTerminated() { return false; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
    }

    static final class StubSource implements ContentSource {
        final AtomicInteger calls = new AtomicInteger();
        volatile RuntimeException failure;
        volatile boolean available = true;
        final Clock clock;
        StubSource(Clock clock) { this.clock = clock; }
        @Override public String id() { return "stub"; }
        @Override public String displayName() { return "Stub"; }
        @Override public boolean available() { return available; }
        @Override public List<RailDescriptor> rails() {
            return available ? List.of(new RailDescriptor("stub", "a", "Rail A"), new RailDescriptor("stub", "b", "Rail B")) : List.of();
        }
        @Override public Rail rail(String railId) {
            calls.incrementAndGet();
            if (failure != null) throw failure;
            return new Rail(new RailDescriptor("stub", railId, "Rail " + railId),
                    List.of(new ContentItem("i-" + calls.get(), "stub", ContentKind.MOVIE, "Item", null, null, List.of())),
                    clock.instant());
        }
        @Override public Optional<ContentItem> item(String itemId) { return Optional.empty(); }
        @Override public Duration defaultRefreshInterval() { return Duration.ofMinutes(10); }
    }

    /** A named stub with one rail, whose availability and id can be flipped by the test. */
    static final class NamedStubSource implements ContentSource {
        final String id;
        final String railId;
        final AtomicInteger calls = new AtomicInteger();
        volatile boolean available;
        final Clock clock;
        NamedStubSource(String id, String railId, boolean available, Clock clock) {
            this.id = id;
            this.railId = railId;
            this.available = available;
            this.clock = clock;
        }
        @Override public String id() { return id; }
        @Override public String displayName() { return id; }
        @Override public boolean available() { return available; }
        @Override public List<RailDescriptor> rails() {
            return available ? List.of(new RailDescriptor(id, railId, railId)) : List.of();
        }
        @Override public Rail rail(String requestedRailId) {
            calls.incrementAndGet();
            return new Rail(new RailDescriptor(id, requestedRailId, requestedRailId),
                    List.of(new ContentItem("i-" + id + "-" + calls.get(), id, ContentKind.MOVIE, "Item", null, null, List.of())),
                    clock.instant());
        }
        @Override public Optional<ContentItem> item(String itemId) { return Optional.empty(); }
        @Override public Duration defaultRefreshInterval() { return Duration.ofMinutes(10); }
    }

    /** Every rail of every available source in bean order, like the real default, but with order/interval knobs. */
    static final class StubPreferences implements RailPreferences {
        List<String> order = List.of();
        final Map<String, Duration> intervals = new HashMap<>();

        @Override
        public List<RailDescriptor> rails(List<ContentSource> sources) {
            List<RailDescriptor> natural = sources.stream().filter(ContentSource::available)
                    .flatMap(s -> s.rails().stream()).toList();
            if (order.isEmpty()) {
                return natural;
            }
            Map<String, RailDescriptor> byKey = new LinkedHashMap<>();
            natural.forEach(d -> byKey.put(RailSnapshot.key(d), d));
            List<RailDescriptor> ordered = new ArrayList<>();
            order.forEach(key -> {
                RailDescriptor d = byKey.remove(key);
                if (d != null) {
                    ordered.add(d);
                }
            });
            ordered.addAll(byKey.values());
            return ordered;
        }

        @Override
        public Duration refreshInterval(ContentSource source) {
            return intervals.getOrDefault(source.id(), source.defaultRefreshInterval());
        }
    }

    final MutableClock clock = new MutableClock(Instant.parse("2026-09-16T09:00:00Z"), ZoneOffset.UTC);
    final StubSource source = new StubSource(clock);
    final ManualExecutor executor = new ManualExecutor();
    final List<Object> events = new CopyOnWriteArrayList<>();
    final ContentProperties properties = new ContentProperties(
            new ContentProperties.Rails(false, Duration.ofSeconds(15), Duration.ofMinutes(1), 4, Map.of()),
            new ContentProperties.Search(Duration.ofSeconds(8)), "de-DE", "DE");
    final StubPreferences preferences = new StubPreferences();
    final RailCache cache = new RailCache(new ContentSources(List.of(source)), preferences,
            events::add, clock, properties, executor);

    @AfterEach
    void stop() {
        cache.stop();
    }

    private RailSnapshot a() {
        return cache.snapshot("stub", "a").orElseThrow();
    }

    @Test
    void forgettingAnAccountDropsSnapshotsAndRejectsOldInflightResults() {
        cache.snapshots();
        executor.runAll();
        assertThat(a().items()).isNotEmpty();
        cache.refresh("stub", "a");
        cache.invalidateSource("stub");
        assertThat(cache.peek()).isEmpty();
        executor.runAll();
        assertThat(cache.peek()).isEmpty();
        assertThat(a().status()).isEqualTo(RailStatus.LOADING);
        assertThat(a().items()).isEmpty();
        executor.runAll();
        assertThat(a().status()).isEqualTo(RailStatus.READY);
    }

    @Test
    void aPageReadNeverWaitsAndStartsLoadingNeverLoadedRails() {
        List<RailSnapshot> first = cache.snapshots();

        assertThat(first).extracting(RailSnapshot::key).containsExactly("stub/a", "stub/b");
        assertThat(first).allSatisfy(s -> assertThat(s.status()).isEqualTo(RailStatus.LOADING));
        assertThat(source.calls).hasValue(0);
        assertThat(executor.queued).hasSize(2);

        executor.runAll();

        assertThat(a().status()).isEqualTo(RailStatus.READY);
        assertThat(a().items()).hasSize(1);
        assertThat(a().fetchedAt()).isEqualTo(clock.instant());
        assertThat(events).filteredOn(RailsChangedEvent.class::isInstance).hasSize(1);
        assertThat(events).filteredOn(RailUpdatedEvent.class::isInstance)
                .extracting(e -> ((RailUpdatedEvent) e).snapshot().status())
                .contains(RailStatus.READY);
    }

    @Test
    void onlyOneFetchPerRailIsInFlight() {
        cache.snapshots();
        cache.snapshots();
        cache.refresh("stub", "a");
        cache.tick();

        assertThat(executor.queued).hasSize(2);
        assertThat(a().refreshing()).isTrue();
    }

    @Test
    void aRefreshDuringALoadLoadsAgainOnceTheLoadEnds() {
        cache.snapshot("stub", "a");
        cache.refresh("stub", "a");

        executor.runAll();
        assertThat(executor.queued).as("the follow-up load").hasSize(1);
        assertThat(a().refreshing()).isTrue();

        executor.runAll();
        assertThat(source.calls).hasValue(2);
        assertThat(a().items()).extracting(ContentItem::id).containsExactly("i-2");
        assertThat(a().refreshing()).isFalse();
        assertThat(executor.queued).isEmpty();
    }

    @Test
    void refreshesDuringOneLoadAddUpToOneMoreLoad() {
        cache.snapshot("stub", "a");
        cache.refresh("stub", "a");
        cache.refresh("stub", "a");
        cache.refresh("stub", "a");

        executor.runAll();
        executor.runAll();

        assertThat(source.calls).hasValue(2);
        assertThat(executor.queued).isEmpty();
    }

    @Test
    void aRefreshDuringAFailingLoadStillLoadsAgain() {
        source.failure = new ContentSourceException(ContentSourceException.Kind.UNREACHABLE, "Stub is down");
        cache.snapshot("stub", "a");
        cache.refresh("stub", "a");

        executor.runAll();
        source.failure = null;
        executor.runAll();

        assertThat(a().status()).isEqualTo(RailStatus.READY);
        assertThat(a().error()).isNull();
    }

    @Test
    void pageReadsAndTicksDuringALoadAskForNoFollowUp() {
        cache.snapshots();
        cache.snapshots();
        cache.snapshot("stub", "a");
        cache.tick();

        executor.runAll();

        assertThat(executor.queued).isEmpty();
        assertThat(source.calls).hasValue(2);
    }

    @Test
    void anInterruptedLoadStartsNoFollowUp() {
        cache.snapshot("stub", "a");
        cache.refresh("stub", "a");
        Thread.currentThread().interrupt();
        try {
            executor.runAll();
        } finally {
            Thread.interrupted();
        }

        assertThat(executor.queued).isEmpty();
        assertThat(source.calls).hasValue(0);
    }

    @Test
    void refreshesWhenTheSourceIntervalHasPassed() {
        cache.snapshots();
        executor.runAll();

        clock.advance(Duration.ofMinutes(9));
        cache.tick();
        assertThat(executor.queued).isEmpty();

        clock.advance(Duration.ofMinutes(1));
        cache.tick();
        assertThat(executor.queued).hasSize(2);
    }

    @Test
    void aFailureKeepsTheLastItemsAndIsRetriedWithBackoff() {
        cache.snapshots();
        executor.runAll();
        source.failure = new ContentSourceException(ContentSourceException.Kind.UNREACHABLE, "Could not reach Stub (connection refused)");
        clock.advance(Duration.ofMinutes(10));
        cache.tick();
        executor.runAll();

        assertThat(a().status()).isEqualTo(RailStatus.FAILED);
        assertThat(a().error()).isEqualTo("Could not reach Stub (connection refused)");
        assertThat(a().items()).hasSize(1);
        assertThat(a().refreshing()).isFalse();

        clock.advance(Duration.ofSeconds(59));
        cache.tick();
        assertThat(executor.queued).isEmpty();
        clock.advance(Duration.ofSeconds(1));
        cache.tick();
        assertThat(executor.queued).hasSize(2);
        executor.runAll();

        clock.advance(Duration.ofMinutes(1));
        cache.tick();
        assertThat(executor.queued).as("second failure waits two minutes").isEmpty();
        clock.advance(Duration.ofMinutes(1));
        cache.tick();
        assertThat(executor.queued).hasSize(2);
    }

    @Test
    void aManualRetryIgnoresBackoffAndRecovers() {
        source.failure = new ContentSourceException(ContentSourceException.Kind.UNREACHABLE, "Stub is down");
        cache.snapshots();
        executor.runAll();
        assertThat(a().status()).isEqualTo(RailStatus.FAILED);
        assertThat(a().hasItems()).isFalse();

        source.failure = null;
        cache.refresh("stub", "a");
        executor.runAll();

        assertThat(a().status()).isEqualTo(RailStatus.READY);
        assertThat(a().error()).isNull();
    }

    @Test
    void unexpectedExceptionsBecomeAGenericMessage() {
        source.failure = new IllegalStateException("secret-token-123 leaked in a stack");
        cache.snapshots();
        executor.runAll();

        assertThat(a().error()).isEqualTo("Stub could not load Rail A").doesNotContain("secret");
    }

    @Test
    void aRailRemovedDuringFetchingKeepsItsLastItemsWithAnActionableError() {
        cache.snapshots();
        executor.runAll();
        List<ContentItem> lastItems = a().items();
        source.failure = new IllegalArgumentException("unknown rail");

        cache.refresh("stub", "a");
        executor.runAll();

        assertThat(a().status()).isEqualTo(RailStatus.FAILED);
        assertThat(a().error()).isEqualTo("Stub no longer offers Rail A");
        assertThat(a().items()).isEqualTo(lastItems);
        assertThat(a().refreshing()).isFalse();
    }

    @Test
    void anInterruptedFetchCanBeStartedAgain() {
        cache.snapshot("stub", "a");
        events.clear();
        Thread.currentThread().interrupt();
        try {
            executor.runAll();

            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(events).isEmpty();
            assertThat(cache.peek()).filteredOn(snapshot -> snapshot.railId().equals("a"))
                    .singleElement().satisfies(snapshot -> assertThat(snapshot.status()).isEqualTo(RailStatus.LOADING));
        } finally {
            Thread.interrupted();
        }

        cache.refresh("stub", "a");
        executor.runAll();

        assertThat(a().status()).isEqualTo(RailStatus.READY);
        assertThat(a().items()).extracting(ContentItem::id).containsExactly("i-1");
        assertThat(a().refreshing()).isFalse();
    }

    @Test
    void railsThatDisappearAreDroppedAndLateResultsDiscarded() {
        cache.snapshots();
        source.available = false;
        cache.reconcile();
        executor.runAll();

        assertThat(cache.snapshots()).isEmpty();
        assertThat(events).filteredOn(RailsChangedEvent.class::isInstance)
                .extracting(e -> ((RailsChangedEvent) e).keys())
                .containsExactly(List.of("stub/a", "stub/b"), List.of());
    }

    @Test
    void versionsIncreaseWithEveryChange() {
        cache.snapshots();
        long loading = a().version();
        executor.runAll();
        assertThat(a().version()).isGreaterThan(loading);
    }

    /**
     * An open dashboard keeps the versions it saw before the server restarted, and takes only newer ones: a restarted
     * cache must count on from above anything the one before it could have reached.
     */
    @Test
    void aRestartedCacheCountsOnFromAboveTheVersionsBeforeIt() {
        cache.snapshots();
        for (int refresh = 0; refresh < 500; refresh++) {
            cache.refresh("stub", "a");
            executor.runAll();
        }
        long beforeTheRestart = a().version();
        clock.advance(Duration.ofSeconds(1));
        RailCache restarted = new RailCache(new ContentSources(List.of(source)), preferences, events::add, clock,
                properties, executor);
        try {
            restarted.snapshots();

            assertThat(restarted.snapshot("stub", "a").orElseThrow().version()).isGreaterThan(beforeTheRestart);
        } finally {
            restarted.stop();
        }
    }

    @Test
    void peekNeitherReconcilesNorLoads() {
        assertThat(cache.peek()).isEmpty();
        assertThat(executor.queued).isEmpty();
    }

    @Test
    void preferenceChangesReorderAndReschedule() {
        cache.snapshots();
        executor.runAll();
        events.clear();

        preferences.intervals.put("stub", Duration.ofMinutes(2));
        preferences.order = List.of("stub/b", "stub/a");
        cache.onPreferencesChanged(new SourcePreferencesChangedEvent());

        assertThat(events).filteredOn(RailsChangedEvent.class::isInstance)
                .extracting(e -> ((RailsChangedEvent) e).keys())
                .contains(List.of("stub/b", "stub/a"));

        clock.advance(Duration.ofMinutes(2));
        cache.tick();
        assertThat(executor.queued).hasSize(2);
    }

    @Test
    void aContentChangeReconcilesAndRefreshesOnlyThatSource() {
        NamedStubSource sourceA = new NamedStubSource("a", "r1", true, clock);
        NamedStubSource sourceB = new NamedStubSource("b", "r2", true, clock);
        RailCache twoSourceCache = new RailCache(new ContentSources(List.of(sourceA, sourceB)), preferences,
                events::add, clock, properties, executor);
        try {
            twoSourceCache.snapshots();
            executor.runAll();
            assertThat(sourceA.calls).hasValue(1);
            assertThat(sourceB.calls).hasValue(1);

            twoSourceCache.onContentChanged(new ContentChangedEvent("a"));
            executor.runAll();

            assertThat(sourceA.calls).hasValue(2);
            assertThat(sourceB.calls).hasValue(1);
        } finally {
            twoSourceCache.stop();
        }
    }

    @Test
    void aSourceThatBecameAvailableGetsItsRail() {
        NamedStubSource sourceC = new NamedStubSource("c", "pinned", false, clock);
        RailCache oneSourceCache = new RailCache(new ContentSources(List.of(sourceC)), preferences,
                events::add, clock, properties, executor);
        try {
            assertThat(oneSourceCache.snapshots()).noneMatch(s -> s.key().equals("c/pinned"));

            sourceC.available = true;
            events.clear();
            oneSourceCache.onContentChanged(new ContentChangedEvent("c"));
            executor.runAll();

            assertThat(events).filteredOn(RailsChangedEvent.class::isInstance)
                    .extracting(e -> ((RailsChangedEvent) e).keys())
                    .anyMatch(keys -> keys.contains("c/pinned"));
            assertThat(sourceC.calls).hasValue(1);
        } finally {
            oneSourceCache.stop();
        }
    }
}

package dev.andre.homecontrol.content;

import dev.andre.homecontrol.core.content.ContentSource;
import dev.andre.homecontrol.core.content.ContentSourceException;
import dev.andre.homecontrol.core.content.ContentSources;
import dev.andre.homecontrol.core.content.Rail;
import dev.andre.homecontrol.core.content.RailDescriptor;
import dev.andre.homecontrol.core.playback.ContentItem;
import dev.andre.homecontrol.core.playback.ContentKind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class RailCacheTest {

    /** Mutable clock for due-time tests. */
    static final class TestClock extends Clock {
        Instant now = Instant.parse("2026-09-16T09:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void advance(Duration d) { now = now.plus(d); }
    }

    /** Runs tasks only when told to, so "in flight" is observable. */
    static final class ManualExecutor extends AbstractExecutorService {
        final List<Runnable> queued = new ArrayList<>();
        @Override public void execute(Runnable command) { queued.add(command); }
        void runAll() { List<Runnable> now = new ArrayList<>(queued); queued.clear(); now.forEach(Runnable::run); }
        @Override public void shutdown() { }
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

    final TestClock clock = new TestClock();
    final StubSource source = new StubSource(clock);
    final ManualExecutor executor = new ManualExecutor();
    final List<Object> events = new CopyOnWriteArrayList<>();
    final ContentProperties properties = new ContentProperties(
            new ContentProperties.Rails(false, Duration.ofSeconds(15), Duration.ofMinutes(1), 4, Map.of()));
    final RailCache cache = new RailCache(new ContentSources(List.of(source)), new DefaultRailPreferences(properties),
            events::add, clock, properties, executor);

    @AfterEach
    void stop() {
        cache.stop();
    }

    private RailSnapshot a() {
        return cache.snapshot("stub", "a").orElseThrow();
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
        assertThat(a().fetchedAt()).isEqualTo(clock.now);
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
        source.failure = new ContentSourceException("Could not reach Stub (connection refused)");
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
        source.failure = new ContentSourceException("Stub is down");
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

    @Test
    void peekNeitherReconcilesNorLoads() {
        assertThat(cache.peek()).isEmpty();
        assertThat(executor.queued).isEmpty();
    }
}

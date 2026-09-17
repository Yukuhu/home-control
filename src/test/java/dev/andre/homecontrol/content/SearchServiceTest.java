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

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;

class SearchServiceTest {

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    @AfterEach
    void shutdown() {
        executor.close();
    }

    private static final class StubSource implements ContentSource {
        private final String id;
        private final String name;
        private final BiFunction<String, Integer, List<ContentItem>> behavior;
        final AtomicInteger calls = new AtomicInteger();
        volatile String lastQuery;
        volatile Integer lastLimit;

        StubSource(String id, String name, BiFunction<String, Integer, List<ContentItem>> behavior) {
            this.id = id;
            this.name = name;
            this.behavior = behavior;
        }

        @Override public String id() { return id; }
        @Override public String displayName() { return name; }
        @Override public List<RailDescriptor> rails() { return List.of(); }
        @Override public Rail rail(String railId) { throw new UnsupportedOperationException(); }
        @Override public Optional<ContentItem> item(String itemId) { return Optional.empty(); }
        @Override public boolean searchable() { return true; }

        @Override
        public List<ContentItem> search(String query, int limit) {
            calls.incrementAndGet();
            lastQuery = query;
            lastLimit = limit;
            return behavior.apply(query, limit);
        }
    }

    private static final class TestPreferences implements RailPreferences {
        private final Set<String> disabled;
        TestPreferences(String... disabledIds) { disabled = Set.of(disabledIds); }
        @Override public List<RailDescriptor> rails(List<ContentSource> sources) { throw new UnsupportedOperationException(); }
        @Override public Duration refreshInterval(ContentSource source) { throw new UnsupportedOperationException(); }
        @Override public boolean sourceEnabled(String sourceId) { return !disabled.contains(sourceId); }
    }

    private static BiFunction<String, Integer, List<ContentItem>> sleepThenReturn(long millis, List<ContentItem> items) {
        return (query, limit) -> {
            sleep(millis);
            return items;
        };
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    private static ContentItem item(String id, String sourceId, String title) {
        return new ContentItem(id, sourceId, ContentKind.MOVIE, title, null, null, List.of());
    }

    private static ContentProperties properties(Duration timeout) {
        return new ContentProperties(
                new ContentProperties.Rails(false, Duration.ofSeconds(15), Duration.ofMinutes(1), 4, Map.of()),
                new ContentProperties.Search(timeout), "de-DE", "DE");
    }

    @Test
    void queriesSourcesInParallelAndKeepsSourceOrder() {
        StubSource first = new StubSource("first", "First", sleepThenReturn(200, List.of(item("i1", "first", "One"))));
        StubSource second = new StubSource("second", "Second", sleepThenReturn(200, List.of(item("i2", "second", "Two"))));
        ContentSources sources = new ContentSources(List.of(first, second));
        SearchService service = new SearchService(sources, new TestPreferences(), properties(Duration.ofMillis(300)), executor);

        long start = System.nanoTime();
        SearchOutcome outcome = service.search("q", 10);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMs).isLessThan(350);
        assertThat(outcome.hits()).hasSize(2);
        assertThat(outcome.hits().get(0).source()).isEqualTo(first);
        assertThat(outcome.hits().get(1).source()).isEqualTo(second);
        assertThat(outcome.failures()).isEmpty();
    }

    @Test
    void aSlowSourceBecomesAFailureAndOthersStillAnswer() {
        StubSource slow = new StubSource("slow", "Slow", sleepThenReturn(2000, List.of()));
        StubSource fast = new StubSource("fast", "Fast", sleepThenReturn(0, List.of(item("i1", "fast", "One"))));
        ContentSources sources = new ContentSources(List.of(slow, fast));
        SearchService service = new SearchService(sources, new TestPreferences(), properties(Duration.ofMillis(300)), executor);

        long start = System.nanoTime();
        SearchOutcome outcome = service.search("q", 10);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMs).isLessThan(600);
        assertThat(outcome.failures()).hasSize(1);
        assertThat(outcome.failures().get(0).message()).isEqualTo("Slow did not answer in time");
        assertThat(outcome.hits()).hasSize(1);
        assertThat(outcome.hits().get(0).source()).isEqualTo(fast);
    }

    @Test
    void aContentSourceExceptionKeepsItsMessage() {
        StubSource broken = new StubSource("broken", "Broken", (q, l) -> {
            throw new ContentSourceException("Broken is unreachable");
        });
        ContentSources sources = new ContentSources(List.of(broken));
        SearchService service = new SearchService(sources, new TestPreferences(), properties(Duration.ofMillis(300)), executor);

        SearchOutcome outcome = service.search("q", 10);

        assertThat(outcome.failures()).hasSize(1);
        assertThat(outcome.failures().get(0).message()).isEqualTo("Broken is unreachable");
    }

    @Test
    void anUnexpectedExceptionIsGeneric() {
        StubSource broken = new StubSource("broken", "Broken", (q, l) -> {
            throw new IllegalStateException("kaboom");
        });
        ContentSources sources = new ContentSources(List.of(broken));
        SearchService service = new SearchService(sources, new TestPreferences(), properties(Duration.ofMillis(300)), executor);

        SearchOutcome outcome = service.search("q", 10);

        assertThat(outcome.failures()).hasSize(1);
        assertThat(outcome.failures().get(0).message()).isEqualTo("Broken could not search");
        assertThat(outcome.failures().get(0).message()).doesNotContain("kaboom");
    }

    @Test
    void disabledSourcesAreNotQueried() {
        StubSource tube = new StubSource("tube", "Tube", sleepThenReturn(0, List.of()));
        StubSource jellyfin = new StubSource("jellyfin", "Jellyfin", sleepThenReturn(0, List.of(item("i1", "jellyfin", "One"))));
        ContentSources sources = new ContentSources(List.of(tube, jellyfin));
        SearchService service = new SearchService(sources, new TestPreferences("tube"), properties(Duration.ofMillis(300)), executor);

        SearchOutcome outcome = service.search("q", 10);

        assertThat(tube.calls.get()).isZero();
        assertThat(outcome.hits()).hasSize(1);
        assertThat(outcome.hits().get(0).source()).isEqualTo(jellyfin);
    }

    @Test
    void passesQueryAndLimitThrough() {
        StubSource source = new StubSource("jellyfin", "Jellyfin", sleepThenReturn(0, List.of()));
        ContentSources sources = new ContentSources(List.of(source));
        SearchService service = new SearchService(sources, new TestPreferences(), properties(Duration.ofMillis(300)), executor);

        service.search("bunny", 42);

        assertThat(source.lastQuery).isEqualTo("bunny");
        assertThat(source.lastLimit).isEqualTo(42);
    }
}

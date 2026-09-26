package dev.andre.homecontrol.content;

import dev.andre.homecontrol.core.content.ContentSource;
import dev.andre.homecontrol.core.content.ContentSourceException;
import dev.andre.homecontrol.core.content.ContentSources;
import dev.andre.homecontrol.core.playback.ContentItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** One query, every enabled searchable source, in parallel, with one deadline (spec §6.1 search). */
public class SearchService {

    private static final String TIMEOUT_MESSAGE = " did not answer in time";

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);

    private final ContentSources sources;
    private final RailPreferences preferences;
    private final ContentProperties properties;
    private final ExecutorService executor;

    public SearchService(ContentSources sources, RailPreferences preferences, ContentProperties properties,
                         ExecutorService executor) {
        this.sources = sources;
        this.preferences = preferences;
        this.properties = properties;
        this.executor = executor;
    }

    public SearchOutcome search(String query, int limit) {
        Map<ContentSource, Future<List<ContentItem>>> pending = new LinkedHashMap<>();
        for (ContentSource source : sources.searchable()) {
            if (preferences.sourceEnabled(source.id()) && !source.searchOnDemand()) {
                pending.put(source, executor.submit(() -> source.search(query, limit)));
            }
        }
        return collect(query, pending);
    }

    /** Runs one source's search on demand, e.g. after the user presses "Search YouTube". */
    public SearchOutcome searchSource(String sourceId, String query, int limit) {
        ContentSource source = sources.searchable().stream()
                .filter(candidate -> candidate.id().equals(sourceId) && preferences.sourceEnabled(sourceId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No searchable source " + sourceId));
        Map<ContentSource, Future<List<ContentItem>>> pending = new LinkedHashMap<>();
        pending.put(source, executor.submit(() -> source.search(query, limit)));
        return collect(query, pending);
    }

    /** Searchable, available, enabled sources that are left out of the unified search and offered on demand. */
    public List<ContentSource> onDemandSources() {
        return sources.searchable().stream()
                .filter(source -> source.searchOnDemand() && preferences.sourceEnabled(source.id()))
                .toList();
    }

    private SearchOutcome collect(String query, Map<ContentSource, Future<List<ContentItem>>> pending) {
        long deadline = System.nanoTime() + properties.search().timeout().toNanos();
        List<SearchOutcome.Hits> hits = new ArrayList<>();
        List<SearchOutcome.Failure> failures = new ArrayList<>();
        boolean interrupted = false;
        for (Map.Entry<ContentSource, Future<List<ContentItem>>> entry : pending.entrySet()) {
            ContentSource source = entry.getKey();
            Future<List<ContentItem>> future = entry.getValue();
            if (interrupted) {
                future.cancel(true);
                failures.add(new SearchOutcome.Failure(source, source.displayName() + TIMEOUT_MESSAGE));
                continue;
            }
            try {
                long remaining = Math.max(0, deadline - System.nanoTime());
                hits.add(new SearchOutcome.Hits(source, future.get(remaining, TimeUnit.NANOSECONDS)));
            } catch (TimeoutException _) {
                future.cancel(true);
                failures.add(new SearchOutcome.Failure(source, source.displayName() + TIMEOUT_MESSAGE));
            } catch (ExecutionException e) {
                if (e.getCause() instanceof ContentSourceException cause) {
                    failures.add(new SearchOutcome.Failure(source, cause.getMessage()));
                } else {
                    log.warn("{} search failed", source.id(), e.getCause());
                    failures.add(new SearchOutcome.Failure(source, source.displayName() + " could not search"));
                }
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                interrupted = true;
                future.cancel(true);
                failures.add(new SearchOutcome.Failure(source, source.displayName() + TIMEOUT_MESSAGE));
            }
        }
        return new SearchOutcome(query, hits, failures);
    }

    /** Releases the executor backing this service. */
    public void close() {
        executor.close();
    }
}

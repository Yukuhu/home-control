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
            if (preferences.sourceEnabled(source.id())) {
                pending.put(source, executor.submit(() -> source.search(query, limit)));
            }
        }
        long deadline = System.nanoTime() + properties.search().timeout().toNanos();
        List<SearchOutcome.Hits> hits = new ArrayList<>();
        List<SearchOutcome.Failure> failures = new ArrayList<>();
        boolean interrupted = false;
        for (Map.Entry<ContentSource, Future<List<ContentItem>>> entry : pending.entrySet()) {
            ContentSource source = entry.getKey();
            Future<List<ContentItem>> future = entry.getValue();
            if (interrupted) {
                future.cancel(true);
                failures.add(new SearchOutcome.Failure(source, source.displayName() + " did not answer in time"));
                continue;
            }
            try {
                long remaining = Math.max(0, deadline - System.nanoTime());
                hits.add(new SearchOutcome.Hits(source, future.get(remaining, TimeUnit.NANOSECONDS)));
            } catch (TimeoutException e) {
                future.cancel(true);
                failures.add(new SearchOutcome.Failure(source, source.displayName() + " did not answer in time"));
            } catch (ExecutionException e) {
                if (e.getCause() instanceof ContentSourceException cause) {
                    failures.add(new SearchOutcome.Failure(source, cause.getMessage()));
                } else {
                    log.warn("{} search failed", source.id(), e.getCause());
                    failures.add(new SearchOutcome.Failure(source, source.displayName() + " could not search"));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                interrupted = true;
                future.cancel(true);
                failures.add(new SearchOutcome.Failure(source, source.displayName() + " did not answer in time"));
            }
        }
        return new SearchOutcome(query, hits, failures);
    }

    /** Releases the executor backing this service. */
    public void close() {
        executor.close();
    }
}

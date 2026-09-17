package dev.andre.homecontrol.content;

import dev.andre.homecontrol.core.content.ContentChangedEvent;
import dev.andre.homecontrol.core.content.ContentSource;
import dev.andre.homecontrol.core.content.ContentSourceException;
import dev.andre.homecontrol.core.content.ContentSources;
import dev.andre.homecontrol.core.content.Rail;
import dev.andre.homecontrol.core.content.RailDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.event.EventListener;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-rail cache in front of every content source (spec §7): page loads read snapshots, a
 * scheduler refreshes them on virtual threads, and every change is published for SSE.
 */
public class RailCache implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(RailCache.class);

    private final ContentSources sources;
    private final RailPreferences preferences;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final ContentProperties.Rails properties;
    private final ExecutorService fetches;
    private final Semaphore permits;
    private final AtomicLong versions = new AtomicLong();

    /** Guarded by {@code this}; iteration order is display order. */
    private Map<String, Entry> entries = new LinkedHashMap<>();
    private volatile ScheduledExecutorService ticker;
    private volatile boolean running;

    private static final class Entry {
        final RailDescriptor descriptor;
        RailSnapshot snapshot;
        Instant dueAt;
        int failures;
        boolean inFlight;

        Entry(RailDescriptor descriptor, RailSnapshot snapshot, Instant dueAt) {
            this.descriptor = descriptor;
            this.snapshot = snapshot;
            this.dueAt = dueAt;
        }
    }

    public RailCache(ContentSources sources, RailPreferences preferences, ApplicationEventPublisher events,
                     Clock clock, ContentProperties properties, ExecutorService fetches) {
        this.sources = sources;
        this.preferences = preferences;
        this.events = events;
        this.clock = clock;
        this.properties = properties.rails();
        this.fetches = fetches;
        this.permits = new Semaphore(this.properties.maxConcurrentFetches());
    }

    public List<RailSnapshot> snapshots() {
        reconcile();
        List<Entry> toStart = new ArrayList<>();
        List<RailSnapshot> result = new ArrayList<>();
        synchronized (this) {
            for (Entry entry : entries.values()) {
                if (entry.snapshot.status() == RailStatus.LOADING && !entry.inFlight) {
                    toStart.add(entry);
                }
            }
        }
        toStart.forEach(this::start);
        synchronized (this) {
            entries.values().forEach(entry -> result.add(entry.snapshot));
        }
        return result;
    }

    public synchronized List<RailSnapshot> peek() {
        return entries.values().stream().map(entry -> entry.snapshot).toList();
    }

    public Optional<RailSnapshot> snapshot(String sourceId, String railId) {
        reconcile();
        Entry entry;
        synchronized (this) {
            entry = entries.get(sourceId + "/" + railId);
        }
        if (entry == null) {
            return Optional.empty();
        }
        boolean load;
        synchronized (this) {
            load = entry.snapshot.status() == RailStatus.LOADING && !entry.inFlight;
        }
        if (load) {
            start(entry);
        }
        synchronized (this) {
            return Optional.of(entry.snapshot);
        }
    }

    public Optional<RailSnapshot> refresh(String sourceId, String railId) {
        reconcile();
        Entry entry;
        synchronized (this) {
            entry = entries.get(sourceId + "/" + railId);
        }
        if (entry == null) {
            return Optional.empty();
        }
        start(entry);
        synchronized (this) {
            return Optional.of(entry.snapshot);
        }
    }

    public void tick() {
        reconcile();
        Instant now = clock.instant();
        List<Entry> due = new ArrayList<>();
        synchronized (this) {
            for (Entry entry : entries.values()) {
                if (!entry.inFlight && !entry.dueAt.isAfter(now)) {
                    due.add(entry);
                }
            }
        }
        due.forEach(this::start);
    }

    public void reconcile() {
        List<RailDescriptor> wanted = preferences.rails(sources.all());
        List<String> keys = null;
        synchronized (this) {
            Map<String, Entry> next = new LinkedHashMap<>();
            for (RailDescriptor descriptor : wanted) {
                String key = RailSnapshot.key(descriptor);
                Entry existing = entries.get(key);
                if (existing != null && existing.descriptor.equals(descriptor)) {
                    next.put(key, existing);
                } else if (!next.containsKey(key)) {
                    next.put(key, new Entry(descriptor,
                            RailSnapshot.loading(descriptor, versions.incrementAndGet()), clock.instant()));
                }
            }
            if (!List.copyOf(next.keySet()).equals(List.copyOf(entries.keySet()))) {
                keys = List.copyOf(next.keySet());
            }
            entries = next;
        }
        if (keys != null) {
            events.publishEvent(new RailsChangedEvent(keys));
        }
    }

    @EventListener
    public void onPreferencesChanged(SourcePreferencesChangedEvent event) {
        reschedule();
    }

    /** A source said its rails changed (e.g. a new pin): pick up new rails and refetch that source now. */
    @EventListener
    public void onContentChanged(ContentChangedEvent event) {
        reconcile();
        for (RailSnapshot snapshot : peek()) {
            if (snapshot.sourceId().equals(event.sourceId())) {
                refresh(snapshot.sourceId(), snapshot.railId());
            }
        }
    }

    /** Re-evaluates due times after preferences changed (Task 4 calls this through an event). */
    public void reschedule() {
        Instant now = clock.instant();
        synchronized (this) {
            for (Entry entry : entries.values()) {
                Optional<ContentSource> source = sources.find(entry.descriptor.sourceId());
                if (source.isPresent() && entry.snapshot.fetchedAt() != null && entry.failures == 0) {
                    Instant due = entry.snapshot.fetchedAt().plus(preferences.refreshInterval(source.get()));
                    entry.dueAt = due.isBefore(now) ? now : due;
                }
            }
        }
        reconcile();
    }

    private void start(Entry entry) {
        RailSnapshot marked;
        synchronized (this) {
            if (entry.inFlight || entries.get(RailSnapshot.key(entry.descriptor)) != entry) {
                return;
            }
            entry.inFlight = true;
            entry.snapshot = entry.snapshot.refreshing(versions.incrementAndGet());
            marked = entry.snapshot;
        }
        events.publishEvent(new RailUpdatedEvent(marked));
        try {
            fetches.execute(() -> fetch(entry));
        } catch (RejectedExecutionException e) {
            synchronized (this) {
                entry.inFlight = false;
            }
        }
    }

    private void fetch(Entry entry) {
        RailDescriptor descriptor = entry.descriptor;
        Optional<ContentSource> source = sources.find(descriptor.sourceId());
        RailSnapshot published;
        try {
            permits.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            synchronized (this) {
                entry.inFlight = false;
            }
            return;
        }
        try {
            if (source.isEmpty()) {
                published = fail(entry, descriptor.sourceId() + " is switched off", Duration.ofMinutes(15));
            } else {
                ContentSource found = source.get();
                Duration interval = preferences.refreshInterval(found);
                try {
                    Rail rail = found.rail(descriptor.id());
                    published = succeed(entry, rail, interval);
                } catch (ContentSourceException e) {
                    published = fail(entry, e.getMessage(), interval);
                } catch (IllegalArgumentException e) {
                    published = fail(entry, found.displayName() + " no longer offers " + descriptor.title(), interval);
                } catch (RuntimeException e) {
                    log.warn("Rail {} failed to load", RailSnapshot.key(descriptor), e);
                    published = fail(entry, found.displayName() + " could not load " + descriptor.title(), interval);
                }
            }
        } finally {
            permits.release();
        }
        if (published != null) {
            events.publishEvent(new RailUpdatedEvent(published));
        }
    }

    private RailSnapshot succeed(Entry entry, Rail rail, Duration interval) {
        synchronized (this) {
            entry.inFlight = false;
            if (entries.get(RailSnapshot.key(entry.descriptor)) != entry) {
                return null;
            }
            Instant fetchedAt = rail.fetchedAt() == null ? clock.instant() : rail.fetchedAt();
            entry.snapshot = entry.snapshot.ready(rail.items(), fetchedAt, versions.incrementAndGet());
            entry.failures = 0;
            entry.dueAt = clock.instant().plus(interval);
            return entry.snapshot;
        }
    }

    private RailSnapshot fail(Entry entry, String message, Duration interval) {
        synchronized (this) {
            entry.inFlight = false;
            if (entries.get(RailSnapshot.key(entry.descriptor)) != entry) {
                return null;
            }
            entry.failures++;
            long factor = 1L << Math.min(entry.failures - 1, 20);
            Duration backoff = properties.retryAfterFailure().multipliedBy(factor);
            entry.dueAt = clock.instant().plus(backoff.compareTo(interval) < 0 ? backoff : interval);
            entry.snapshot = entry.snapshot.failed(message, versions.incrementAndGet());
            return entry.snapshot;
        }
    }

    @Override
    public void start() {
        running = true;
        if (!properties.schedulerEnabled()) {
            return;
        }
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "rail-cache-ticker");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                tick();
            } catch (RuntimeException e) {
                log.warn("Rail refresh tick failed", e);
            }
        }, 0, properties.tick().toMillis(), TimeUnit.MILLISECONDS);
        ticker = scheduler;
    }

    @Override
    public void stop() {
        running = false;
        ScheduledExecutorService scheduler = ticker;
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        fetches.shutdownNow();
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}

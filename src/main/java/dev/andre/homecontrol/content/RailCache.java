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
    /**
     * Starts at the clock's milliseconds times a thousand, so a restarted cache counts on from above anything the one
     * before it reached: an open dashboard keeps the versions it saw and takes only newer ones. Still below 2^53, where
     * a browser's numbers stop being exact, for another two centuries.
     */
    private final AtomicLong versions;

    /** Guarded by {@code this}; iteration order is display order. */
    private Map<String, Entry> entries = new LinkedHashMap<>();
    // Assigned in start() and only read (never read-modify-written) in stop(); the executor is thread-safe.
    @SuppressWarnings("java:S3077")
    private volatile ScheduledExecutorService ticker;
    private volatile boolean running;

    private static final class Entry {
        final RailDescriptor descriptor;
        RailSnapshot snapshot;
        Instant dueAt;
        int failures;
        boolean inFlight;
        /** A refresh was asked for while a load ran: one more load starts when it ends. */
        boolean loadAgain;

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
        this.versions = new AtomicLong(clock.millis() * 1000);
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

    /** An account changed: discard its snapshots, including results from fetches already in flight. */
    public void invalidateSource(String sourceId) {
        List<String> keys;
        synchronized (this) {
            if (!entries.values().removeIf(entry -> entry.descriptor.sourceId().equals(sourceId))) return;
            keys = List.copyOf(entries.keySet());
        }
        events.publishEvent(new RailsChangedEvent(keys));
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

    /**
     * Loads the rail now. If it is loading already, that load may have begun before whatever made the caller ask
     * (a settings change, a new pin), so one more load follows it.
     */
    public Optional<RailSnapshot> refresh(String sourceId, String railId) {
        reconcile();
        Entry entry;
        synchronized (this) {
            entry = entries.get(sourceId + "/" + railId);
        }
        if (entry == null) {
            return Optional.empty();
        }
        start(entry, true);
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
                } else {
                    next.computeIfAbsent(key, ignored -> new Entry(descriptor,
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
        start(entry, false);
    }

    /** Starts a load unless one runs; {@code loadAgainIfLoading} asks for one more after the running one. */
    private void start(Entry entry, boolean loadAgainIfLoading) {
        RailSnapshot marked;
        synchronized (this) {
            if (entries.get(RailSnapshot.key(entry.descriptor)) != entry) {
                return;
            }
            if (entry.inFlight) {
                entry.loadAgain |= loadAgainIfLoading;
                return;
            }
            entry.inFlight = true;
            entry.snapshot = entry.snapshot.refreshing(versions.incrementAndGet());
            marked = entry.snapshot;
        }
        events.publishEvent(new RailUpdatedEvent(marked));
        try {
            fetches.execute(() -> fetch(entry));
        } catch (RejectedExecutionException _) {
            synchronized (this) {
                entry.inFlight = false;
                entry.loadAgain = false;
            }
        }
    }

    /** Whether a refresh was asked for while the load that just ended ran; answers each request once. */
    private synchronized boolean takeLoadAgain(Entry entry) {
        boolean again = entry.loadAgain;
        entry.loadAgain = false;
        return again;
    }

    private void fetch(Entry entry) {
        RailDescriptor descriptor = entry.descriptor;
        Optional<ContentSource> source = sources.find(descriptor.sourceId());
        RailSnapshot published;
        try {
            permits.acquire();
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            synchronized (this) {
                entry.inFlight = false;
                entry.loadAgain = false;
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
                } catch (IllegalArgumentException _) {
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
        if (takeLoadAgain(entry)) {
            start(entry);
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

    /**
     * Stops the scheduled refreshes; {@link #start()} resumes them. Loads already running finish, and page reads
     * still load rails, as with the scheduler switched off.
     */
    @Override
    public void stop() {
        running = false;
        ScheduledExecutorService scheduler = ticker;
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    /** Ends the cache when the application closes: stops the scheduled refreshes and interrupts running loads. */
    public void close() {
        stop();
        fetches.shutdownNow();
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}

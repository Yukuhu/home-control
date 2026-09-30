package dev.andre.homecontrol.sources.sports.feed;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

/**
 * At most one fetch per key at a time. A caller that finds its key's fetch running waits for that one instead of
 * starting another. The fetch runs on the thread of the caller that started it; no lock is held while anyone waits.
 */
public final class FeedFetches<K> {

    /** How long a feed that failed is left alone before it is tried again. */
    public static final Duration RETRY_BACKOFF = Duration.ofMinutes(10);

    private final ConcurrentHashMap<K, FutureTask<Void>> running = new ConcurrentHashMap<>();

    /**
     * Runs {@code fetch} for {@code key}, or waits for the fetch already running for it, and returns when that one is
     * done. What the fetch throws reaches the caller that ran it and every caller that waited. A waiter that is
     * interrupted returns at once, with its interrupt flag set.
     */
    public void run(K key, Runnable fetch) {
        FutureTask<Void> mine = new FutureTask<>(fetch, null);
        FutureTask<Void> theirs = running.putIfAbsent(key, mine);
        if (theirs != null) {
            await(theirs);
            return;
        }
        try {
            mine.run();
        } finally {
            running.remove(key, mine);
        }
        await(mine);
    }

    private static void await(FutureTask<Void> task) {
        try {
            task.get();
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            switch (e.getCause()) {
                case RuntimeException runtime -> throw runtime;
                case Error error -> throw error;
                default -> throw new IllegalStateException(e.getCause());
            }
        }
    }
}

package dev.andre.homecontrol.sources.sports.feed;

import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import static org.awaitility.Awaitility.await;

/**
 * Test help for callers that should wait for a fetch another caller runs. Each method returns once the caller is
 * parked inside {@link FeedFetches}, so a test that then releases or interrupts it knows it is waiting there, not
 * still on its way.
 */
public final class FeedFetchWaiters {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private FeedFetchWaiters() {
    }

    /** Submits {@code call} to {@code pool} and returns its future once the call waits in {@link FeedFetches}. */
    public static <T> Future<T> submitWaiting(ExecutorService pool, Callable<T> call) {
        AtomicReference<Thread> caller = new AtomicReference<>();
        Future<T> future = pool.submit(() -> {
            caller.set(Thread.currentThread());
            return call.call();
        });
        await().atMost(TIMEOUT).until(() -> caller.get() != null);
        awaitWaiting(caller.get());
        return future;
    }

    /** Starts {@code task} on a virtual thread and returns the thread once it waits in {@link FeedFetches}. */
    public static Thread startWaiting(Runnable task) {
        Thread caller = Thread.ofVirtual().start(task);
        awaitWaiting(caller);
        return caller;
    }

    private static void awaitWaiting(Thread caller) {
        await().atMost(TIMEOUT).until(() -> caller.getState() == Thread.State.WAITING
                && Arrays.stream(caller.getStackTrace())
                        .anyMatch(frame -> frame.getClassName().equals(FeedFetches.class.getName())));
    }
}

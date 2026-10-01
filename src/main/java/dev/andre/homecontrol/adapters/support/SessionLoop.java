package dev.andre.homecontrol.adapters.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * A session's one thread, a virtual one, with at most one pending one-shot task. Once {@link #close()} has run, every
 * call is a no-op that returns false: a caller racing close() never sees a {@link RejectedExecutionException}.
 */
public final class SessionLoop implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SessionLoop.class);

    private final String name;
    private final ScheduledExecutorService executor;
    private final Object lock = new Object();
    private ScheduledFuture<?> pending; // guarded by lock
    private volatile boolean closed;

    public SessionLoop(String name) {
        this.name = name;
        this.executor = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name(name).factory());
    }

    public boolean execute(Runnable task) {
        if (closed) {
            return false;
        }
        try {
            executor.execute(task);
            return true;
        } catch (RejectedExecutionException _) {
            return false;
        }
    }

    /** Runs {@code task} after {@code delay} as the one pending task, replacing the one scheduled before. */
    public boolean schedule(Runnable task, Duration delay) {
        synchronized (lock) {
            cancelPendingLocked();
            if (closed) {
                return false;
            }
            try {
                pending = executor.schedule(task, delay.toMillis(), TimeUnit.MILLISECONDS);
                return true;
            } catch (RejectedExecutionException _) {
                return false;
            }
        }
    }

    /** Drops the pending task if it has not started. */
    public void cancelPending() {
        synchronized (lock) {
            cancelPendingLocked();
        }
    }

    /** Runs {@code task} every {@code interval} after the previous run ends; a failed run is logged, the next still comes. */
    public boolean every(Runnable task, Duration interval) {
        if (closed) {
            return false;
        }
        try {
            executor.scheduleWithFixedDelay(() -> {
                try {
                    task.run();
                } catch (RuntimeException e) {
                    log.warn("{}: a periodic task failed", name, e);
                }
            }, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (RejectedExecutionException _) {
            return false;
        }
    }

    /** Cancels everything and interrupts a task that is running. */
    @Override
    public void close() {
        closed = true;
        executor.shutdownNow();
    }

    private void cancelPendingLocked() {
        if (pending != null) {
            pending.cancel(false);
            pending = null;
        }
    }
}

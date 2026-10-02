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
 * A session's one thread, a virtual one. A one-shot task waits in a {@link Timer}, which holds at most one: the loop's
 * own ({@link #schedule}), or one a session takes for a second wait ({@link #timer()}). Once {@link #close()} has run,
 * every call is a no-op that returns false: a caller racing close() never sees a {@link RejectedExecutionException}.
 * A task that throws is logged with the loop's name: a {@link RuntimeException} as a warning, and the loop goes on; an
 * {@link Error} as an error, and it still ends that task (a periodic one for good).
 */
public final class SessionLoop implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SessionLoop.class);

    private final String name;
    private final ScheduledExecutorService executor;
    private final Timer pending = new Timer();
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
            executor.execute(logged(task));
            return true;
        } catch (RejectedExecutionException _) {
            return false;
        }
    }

    /** Runs {@code task} after {@code delay} as the loop's pending task, replacing the one scheduled before. */
    public boolean schedule(Runnable task, Duration delay) {
        return pending.schedule(task, delay);
    }

    /** The name its thread and its log lines carry, such as {@code shield-session-<device id>}. */
    public String name() {
        return name;
    }

    /** Drops the loop's pending task if it has not started. */
    public void cancelPending() {
        pending.cancel();
    }

    /** A pending-task slot of its own, for a session that waits for two things at once. */
    public Timer timer() {
        return new Timer();
    }

    /** Runs {@code task} every {@code interval} after the previous run ends; a failed run is logged, the next still comes. */
    public boolean every(Runnable task, Duration interval) {
        if (closed) {
            return false;
        }
        try {
            executor.scheduleWithFixedDelay(logged(task), interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
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

    // An Error is logged where it happens, or the executor would keep it unseen in a future, and rethrown, so it
    // still ends the task.
    @SuppressWarnings({"java:S1181", "java:S2139"})
    private Runnable logged(Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                log.warn("{}: a task failed", name, e);
            } catch (Error e) {
                log.error("{}: a task failed", name, e);
                throw e;
            }
        };
    }

    /** At most one pending one-shot task on this loop: scheduling another replaces it. */
    public final class Timer {

        private final Object lock = new Object();
        private ScheduledFuture<?> task; // guarded by lock

        private Timer() {
        }

        public boolean schedule(Runnable next, Duration delay) {
            synchronized (lock) {
                cancelLocked();
                if (closed) {
                    return false;
                }
                try {
                    task = executor.schedule(logged(next), delay.toMillis(), TimeUnit.MILLISECONDS);
                    return true;
                } catch (RejectedExecutionException _) {
                    return false;
                }
            }
        }

        /** Drops the pending task if it has not started. */
        public void cancel() {
            synchronized (lock) {
                cancelLocked();
            }
        }

        private void cancelLocked() {
            if (task != null) {
                task.cancel(false);
                task = null;
            }
        }
    }
}

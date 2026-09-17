package dev.andre.homecontrol.adapters.upnp.protocol;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * One device's loop on one virtual thread: connect with doubling backoff, then poll at the delay
 * the link chooses. An {@link IOException} from a poll means the device is gone; anything else is
 * logged and polling continues. All {@link Link} callbacks run on the loop thread.
 */
public final class ReconnectingPoller implements AutoCloseable {

    public interface Link {
        void connect() throws Exception;

        void poll() throws Exception;

        Duration nextPollDelay();

        void disconnected(Exception cause);
    }

    private static final Logger log = LoggerFactory.getLogger(ReconnectingPoller.class);

    private final String name;
    private final Duration initialBackoff;
    private final Duration maxBackoff;
    private final Link link;
    private final ScheduledExecutorService loop;

    private volatile boolean connected;
    private volatile boolean closed;
    /** Loop thread only. */
    private Duration backoff;
    private ScheduledFuture<?> pending;

    public ReconnectingPoller(String name, Duration initialBackoff, Duration maxBackoff, Link link) {
        this.name = name;
        this.initialBackoff = initialBackoff;
        this.maxBackoff = maxBackoff;
        this.link = link;
        this.backoff = initialBackoff;
        this.loop = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name(name).factory());
    }

    public void start() {
        submit(this::attemptConnect);
    }

    public boolean connected() {
        return connected && !closed;
    }

    public void pollNow() {
        submit(() -> {
            if (connected) {
                cancelPending();
                pollOnce();
            }
        });
    }

    public void reconnectNow() {
        submit(() -> {
            if (!connected) {
                cancelPending();
                backoff = initialBackoff;
                attemptConnect();
            }
        });
    }

    private void attemptConnect() {
        if (closed) {
            return;
        }
        try {
            link.connect();
            connected = true;
            backoff = initialBackoff;
            schedule(this::pollOnce, link.nextPollDelay());
        } catch (Exception e) {
            connected = false;
            link.disconnected(e);
            scheduleReconnect();
        }
    }

    private void pollOnce() {
        if (closed || !connected) {
            return;
        }
        try {
            link.poll();
        } catch (IOException e) {
            connected = false;
            link.disconnected(e);
            scheduleReconnect();
            return;
        } catch (Exception e) {
            log.debug("{}: poll failed: {}", name, e.getMessage());
        }
        schedule(this::pollOnce, link.nextPollDelay());
    }

    private void scheduleReconnect() {
        Duration delay = backoff;
        Duration doubled = backoff.multipliedBy(2);
        backoff = doubled.compareTo(maxBackoff) > 0 ? maxBackoff : doubled;
        schedule(this::attemptConnect, delay);
    }

    private void schedule(Runnable task, Duration delay) {
        cancelPending();
        try {
            pending = loop.schedule(task, delay.toMillis(), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {
            // closing
        }
    }

    private void cancelPending() {
        if (pending != null) {
            pending.cancel(false);
            pending = null;
        }
    }

    private void submit(Runnable task) {
        if (closed) {
            return;
        }
        try {
            loop.execute(task);
        } catch (RejectedExecutionException ignored) {
            // closing
        }
    }

    @Override
    public void close() {
        closed = true;
        connected = false;
        loop.shutdownNow();
    }
}

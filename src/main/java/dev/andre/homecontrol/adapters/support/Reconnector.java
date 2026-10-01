package dev.andre.homecontrol.adapters.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Drives a push-style session's {@code connect()} on its {@link SessionLoop}. The first attempt runs at once.
 * {@code RETRY} tries again after the backoff's next delay; {@code CONNECTED} resets the backoff; {@code PENDING} waits
 * for the session to call {@link #connected()} or {@link #lost()}; {@code STOP} ends the attempts. The session's
 * policies, such as when to stop, stay in its {@code connect()}.
 */
public final class Reconnector {

    public enum Outcome { CONNECTED, PENDING, RETRY, STOP }

    private static final Logger log = LoggerFactory.getLogger(Reconnector.class);

    private final SessionLoop loop;
    private final Backoff backoff;
    private final Supplier<Outcome> connect;
    private volatile boolean stopped;

    public Reconnector(SessionLoop loop, Backoff backoff, Supplier<Outcome> connect) {
        this.loop = loop;
        this.backoff = backoff;
        this.connect = connect;
    }

    public void start() {
        loop.execute(this::attempt);
    }

    /** The connection dropped: try again after the backoff's next delay. */
    public void lost() {
        if (!stopped) {
            loop.schedule(this::attempt, backoff.next());
        }
    }

    /** A {@code PENDING} attempt became usable. */
    public void connected() {
        backoff.reset();
    }

    /** Tries at once with a fresh backoff, unless stopped. */
    public void reconnectNow() {
        if (stopped) {
            return;
        }
        backoff.reset();
        loop.execute(() -> {
            loop.cancelPending();
            attempt();
        });
    }

    /** Tries after {@code wait} with a fresh backoff, unless stopped: a TV that was just woken. */
    public void retryIn(Duration wait) {
        if (stopped) {
            return;
        }
        backoff.reset();
        loop.schedule(this::attempt, wait);
    }

    public boolean stopped() {
        return stopped;
    }

    private void attempt() {
        if (stopped) {
            return;
        }
        Outcome outcome;
        try {
            outcome = connect.get();
        } catch (RuntimeException e) {
            log.warn("A connection attempt failed unexpectedly", e);
            outcome = Outcome.RETRY;
        }
        switch (outcome) {
            case CONNECTED -> backoff.reset();
            case PENDING -> {
                // The session reports connected() or lost().
            }
            case RETRY -> loop.schedule(this::attempt, backoff.next());
            case STOP -> stopped = true;
        }
    }
}

package dev.andre.homecontrol.adapters.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Drives a push-style session's {@code connect()} on its {@link SessionLoop}. The first attempt runs at once.
 * {@code RETRY} tries again after the backoff's next delay; {@code CONNECTED} resets the backoff; {@code PENDING} waits
 * for the session to call {@link #connected()} or {@link #lost()}; {@code STOP}, like {@link #stop()}, ends the
 * attempts for good: a session that must start over, after a new pairing, is replaced by its owner. While connected,
 * while an attempt waits, or once stopped, {@link #reconnectNow()} and {@link #retryIn} do nothing. The session's
 * policies, such as when to stop, stay in its {@code connect()}.
 */
public final class Reconnector {

    public enum Outcome { CONNECTED, PENDING, RETRY, STOP }

    /** TRYING: no connection; an attempt runs or is scheduled. WAITING: an attempt returned {@code PENDING}. */
    private enum Phase { TRYING, WAITING, CONNECTED, STOPPED }

    private static final Logger log = LoggerFactory.getLogger(Reconnector.class);

    private final SessionLoop loop;
    private final Backoff backoff;
    private final Supplier<Outcome> connect;
    private volatile Phase phase = Phase.TRYING;
    /** The {@link System#nanoTime()} {@link #retryIn} asked to wait until; in the past when nothing was asked. */
    private volatile long waitUntil = System.nanoTime();

    public Reconnector(SessionLoop loop, Backoff backoff, Supplier<Outcome> connect) {
        this.loop = loop;
        this.backoff = backoff;
        this.connect = connect;
    }

    public void start() {
        loop.execute(this::attempt);
    }

    /** The connection dropped, or a pending attempt failed: try again after the backoff's next delay. */
    public void lost() {
        if (phase == Phase.STOPPED) {
            return;
        }
        phase = Phase.TRYING;
        loop.schedule(this::attempt, backoff.next());
    }

    /** A {@code PENDING} attempt became usable. */
    public void connected() {
        if (phase == Phase.WAITING) {
            phase = Phase.CONNECTED;
            backoff.reset();
        }
    }

    /** Ends the attempts for good: the session found that connecting again cannot help. */
    public void stop() {
        phase = Phase.STOPPED;
        loop.cancelPending();
    }

    /** Tries at once with a fresh backoff, unless connected, waiting or stopped. */
    public void reconnectNow() {
        if (phase != Phase.TRYING) {
            return;
        }
        backoff.reset();
        loop.execute(() -> {
            if (phase == Phase.TRYING) {
                loop.cancelPending();
                attempt();
            }
        });
    }

    /** Tries after {@code wait} with a fresh backoff, unless connected, waiting or stopped: a TV that was just woken. */
    public void retryIn(Duration wait) {
        if (phase != Phase.TRYING) {
            return;
        }
        backoff.reset();
        waitUntil = System.nanoTime() + wait.toNanos();
        loop.schedule(this::attempt, wait);
    }

    public boolean stopped() {
        return phase == Phase.STOPPED;
    }

    private void attempt() {
        if (phase != Phase.TRYING) {
            return;
        }
        Outcome outcome;
        try {
            outcome = connect.get();
        } catch (RuntimeException e) {
            log.warn("{}: a connection attempt failed unexpectedly", loop.name(), e);
            outcome = Outcome.RETRY;
        }
        if (phase == Phase.STOPPED) {
            return; // the session stopped while it was connecting
        }
        switch (outcome) {
            case CONNECTED -> {
                phase = Phase.CONNECTED;
                backoff.reset();
            }
            case PENDING -> phase = Phase.WAITING;
            case RETRY -> {
                // A wait asked for while this attempt ran (a TV just woken) still stands, and the backoff stays fresh.
                long asked = waitUntil - System.nanoTime();
                loop.schedule(this::attempt, asked > 0 ? Duration.ofNanos(asked) : backoff.next());
            }
            case STOP -> phase = Phase.STOPPED;
        }
    }
}

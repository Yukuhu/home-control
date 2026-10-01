package dev.andre.homecontrol.adapters.support;

import java.time.Duration;

/** A retry delay that starts at {@code initial} and doubles with every use, up to {@code max}. */
public final class Backoff {

    private final Duration initial;
    private final Duration max;
    private Duration current; // guarded by this

    public Backoff(Duration initial, Duration max) {
        this.initial = initial;
        this.max = max;
        this.current = initial;
    }

    /** The delay to wait now; the following one doubles. */
    public synchronized Duration next() {
        Duration delay = current;
        Duration doubled = current.multipliedBy(2);
        current = doubled.compareTo(max) > 0 ? max : doubled;
        return delay;
    }

    public synchronized void reset() {
        current = initial;
    }
}

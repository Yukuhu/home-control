package dev.andre.homecontrol.adapters.net;

import java.time.Duration;

/** The doubling reconnect backoff the device sessions share. */
public final class Backoff {

    private Backoff() {
    }

    /** Twice {@code current}, but never more than {@code max}. */
    public static Duration next(Duration current, Duration max) {
        Duration doubled = current.multipliedBy(2);
        return doubled.compareTo(max) > 0 ? max : doubled;
    }
}

package dev.andre.homecontrol.adapters.androidtv;

import java.time.Duration;

/**
 * The waits of an {@link AndroidTvSession}: the idle time after which a connection is stale, and the reconnect
 * backoff. Production builds them from {@link AndroidTvProperties}; tests pass milliseconds.
 */
record AndroidTvTimings(Duration staleTimeout, Duration reconnectInitialDelay, Duration reconnectMaxDelay) {

    static AndroidTvTimings from(AndroidTvProperties properties) {
        return new AndroidTvTimings(Duration.ofSeconds(properties.staleTimeoutSeconds()),
                Duration.ofSeconds(properties.reconnectInitialDelaySeconds()),
                Duration.ofSeconds(properties.reconnectMaxDelaySeconds()));
    }
}

package dev.andre.homecontrol.adapters.androidtv;

import java.time.Duration;

/**
 * The waits of an {@link AndroidTvSession}: the idle time after which a connection is stale, the reconnect backoff,
 * and how long launched media counts as playing ({@link InferredPlayback}). Production builds them from
 * {@link AndroidTvProperties} and the playback defaults; tests pass milliseconds.
 */
record AndroidTvTimings(Duration staleTimeout, Duration reconnectInitialDelay, Duration reconnectMaxDelay,
                        Duration playbackAppGrace, Duration playbackExpiryMargin) {

    /** With the production playback waits. */
    AndroidTvTimings(Duration staleTimeout, Duration reconnectInitialDelay, Duration reconnectMaxDelay) {
        this(staleTimeout, reconnectInitialDelay, reconnectMaxDelay, InferredPlayback.APP_GRACE,
                InferredPlayback.EXPIRY_MARGIN);
    }

    static AndroidTvTimings from(AndroidTvProperties properties) {
        return new AndroidTvTimings(properties.staleTimeout(), properties.reconnectInitialDelay(),
                properties.reconnectMaxDelay());
    }
}

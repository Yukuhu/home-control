package dev.andre.homecontrol.adapters.cast;

import java.time.Duration;

/**
 * The waits of a {@link CastSession}. Production builds them from {@link CastProperties}; tests pass milliseconds.
 * {@code customMessageErrorWindow}: receivers validate a custom request synchronously, and media loading continues
 * after the session returns, so a rejection that has not arrived within this window counts as accepted.
 */
record CastTimings(Duration heartbeatInterval, Duration staleTimeout, Duration reconnectInitialDelay,
                   Duration reconnectMaxDelay, Duration commandTimeout, Duration loadTimeout,
                   Duration mediaStatusInterval, Duration customMessageErrorWindow) {

    static final Duration CUSTOM_MESSAGE_ERROR_WINDOW = Duration.ofMillis(750);

    static CastTimings from(CastProperties properties) {
        return new CastTimings(properties.heartbeatInterval(),
                properties.staleTimeout(),
                properties.reconnectInitialDelay(),
                properties.reconnectMaxDelay(),
                properties.commandTimeout(),
                properties.loadTimeout(),
                properties.mediaStatusInterval(),
                CUSTOM_MESSAGE_ERROR_WINDOW);
    }
}

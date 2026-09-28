package dev.andre.homecontrol.adapters.tizen;

import java.time.Duration;

/**
 * The waits of a {@link TizenSession}: how often it polls, the grace after a Wake-on-LAN packet, how long a
 * connection waits for the TV's Allow/Deny, and the cap of the backoff after an unanswered one. Production builds
 * them from {@link TizenProperties}; tests pass milliseconds.
 */
record TizenTimings(Duration pollInterval, Duration wakeGrace, Duration requestTimeout, Duration handshakeBackoffCap) {

    static final Duration HANDSHAKE_BACKOFF_CAP = Duration.ofMinutes(5);

    static TizenTimings from(TizenProperties properties) {
        return new TizenTimings(properties.pollInterval(),
                properties.wakeGrace(),
                properties.requestTimeout(),
                HANDSHAKE_BACKOFF_CAP);
    }
}

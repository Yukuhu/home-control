package dev.andre.homecontrol.adapters.webos;

import java.time.Duration;

/**
 * The waits of a {@link WebOsSession}: the reconnect backoff, the grace after a Wake-on-LAN packet, how often a
 * connected TV is asked a cheap question, and how long the TV's pairing prompt may wait for an answer when
 * registering without a stored key. {@link WebOsSession} never takes that path — it goes unpaired instead of
 * calling {@code register} without a key — so registering with a stored key instead uses the connection's own
 * request timeout, straight from {@link WebOsProperties#requestTimeout()}. Production builds them from
 * {@link WebOsProperties}; tests pass milliseconds.
 */
record WebOsTimings(Duration reconnectInitialDelay, Duration reconnectMaxDelay, Duration wakeGrace,
                    Duration livenessInterval, Duration registerTimeout) {

    static WebOsTimings from(WebOsProperties properties) {
        return new WebOsTimings(properties.reconnectInitialDelay(),
                properties.reconnectMaxDelay(),
                properties.wakeGrace(),
                properties.livenessInterval(),
                properties.requestTimeout());
    }
}

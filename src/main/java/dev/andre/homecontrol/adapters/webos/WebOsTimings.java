package dev.andre.homecontrol.adapters.webos;

import java.time.Duration;

/**
 * The waits of a {@link WebOsSession}: the reconnect backoff, the grace after a Wake-on-LAN packet, how often a
 * connected TV is asked a cheap question, and how long registering with a stored key may take. Production builds
 * them from {@link WebOsProperties}; tests pass milliseconds.
 */
record WebOsTimings(Duration reconnectInitialDelay, Duration reconnectMaxDelay, Duration wakeGrace,
                    Duration livenessInterval, Duration registerTimeout) {

    static WebOsTimings from(WebOsProperties properties) {
        return new WebOsTimings(Duration.ofSeconds(properties.reconnectInitialDelaySeconds()),
                Duration.ofSeconds(properties.reconnectMaxDelaySeconds()),
                Duration.ofSeconds(properties.wakeGraceSeconds()),
                Duration.ofSeconds(properties.livenessIntervalSeconds()),
                Duration.ofSeconds(properties.requestTimeoutSeconds()));
    }
}

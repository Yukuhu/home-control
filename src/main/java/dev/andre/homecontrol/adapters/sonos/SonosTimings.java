package dev.andre.homecontrol.adapters.sonos;

import java.time.Duration;

/** The waits of a {@link SonosSession}. Production builds them from {@link SonosProperties}; tests pass milliseconds. */
record SonosTimings(Duration pollInterval, Duration idlePollInterval, Duration topologyInterval,
                    Duration commandTimeout, Duration reconnectInitialDelay, Duration reconnectMaxDelay) {

    static SonosTimings from(SonosProperties properties) {
        return new SonosTimings(Duration.ofSeconds(properties.pollIntervalSeconds()),
                Duration.ofSeconds(properties.idlePollIntervalSeconds()),
                Duration.ofSeconds(properties.topologyIntervalSeconds()),
                Duration.ofSeconds(properties.commandTimeoutSeconds()),
                Duration.ofSeconds(properties.reconnectInitialDelaySeconds()),
                Duration.ofSeconds(properties.reconnectMaxDelaySeconds()));
    }
}

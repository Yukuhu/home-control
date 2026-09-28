package dev.andre.homecontrol.adapters.sonos;

import java.time.Duration;

/** The waits of a {@link SonosSession}. Production builds them from {@link SonosProperties}; tests pass milliseconds. */
record SonosTimings(Duration pollInterval, Duration idlePollInterval, Duration topologyInterval,
                    Duration commandTimeout, Duration reconnectInitialDelay, Duration reconnectMaxDelay) {

    static SonosTimings from(SonosProperties properties) {
        return new SonosTimings(properties.pollInterval(),
                properties.idlePollInterval(),
                properties.topologyInterval(),
                properties.commandTimeout(),
                properties.reconnectInitialDelay(),
                properties.reconnectMaxDelay());
    }
}

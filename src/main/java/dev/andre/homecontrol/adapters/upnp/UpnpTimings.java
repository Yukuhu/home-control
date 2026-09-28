package dev.andre.homecontrol.adapters.upnp;

import java.time.Duration;

/** The waits of a {@link UpnpSession}. Production builds them from {@link UpnpProperties}; tests pass milliseconds. */
record UpnpTimings(Duration pollInterval, Duration idlePollInterval, Duration commandTimeout,
                   Duration reconnectInitialDelay, Duration reconnectMaxDelay) {

    static UpnpTimings from(UpnpProperties properties) {
        return new UpnpTimings(properties.pollInterval(),
                properties.idlePollInterval(),
                properties.commandTimeout(),
                properties.reconnectInitialDelay(),
                properties.reconnectMaxDelay());
    }
}

package dev.andre.homecontrol.discovery.ssdp;

import java.time.Duration;

/**
 * How often {@link SsdpDiscovery} searches. Production builds it from {@link SsdpProperties}; tests pass
 * milliseconds. Public because tests in other packages construct it directly.
 */
public record SsdpTimings(Duration searchInterval) {

    public static SsdpTimings from(SsdpProperties properties) {
        return new SsdpTimings(properties.searchInterval());
    }
}

package dev.andre.homecontrol.discovery.ssdp;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * One service a device announced. {@code address} is always the address the announcing datagram
 * actually came from — never a claim read out of the (unauthenticated) payload, such as
 * {@code location}'s host, which a forged packet could set to anything. {@code description} is
 * null until it has been fetched, which only happens when {@code location} matches {@code address}
 * (see {@code SsdpDiscovery.isSafeToFetch}).
 */
public record SsdpService(String usn, String type, String address, URI location, Map<String, String> headers,
                          Instant expiresAt, DeviceDescription description) {

    public SsdpService withDescription(DeviceDescription fetched) {
        return new SsdpService(usn, type, address, location, headers, expiresAt, fetched);
    }

    public Optional<String> friendlyName() {
        return Optional.ofNullable(description).map(DeviceDescription::friendlyName);
    }
}

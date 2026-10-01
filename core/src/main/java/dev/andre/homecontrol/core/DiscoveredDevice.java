package dev.andre.homecontrol.core;

import java.util.Map;

/**
 * A device seen on the network by one adapter, paired or not. {@code attributes} carries
 * adapter-specific facts from discovery (for Cast: the mDNS TXT {@code id}, {@code md}, {@code fn}).
 */
public record DiscoveredDevice(String adapterId, String name, String host, int port, Map<String, String> attributes) {

    public DiscoveredDevice {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    public DiscoveredDevice(String adapterId, String name, String host, int port) {
        this(adapterId, name, host, port, Map.of());
    }
}

package dev.andre.shield.discovery;

/** A device seen on the network but not necessarily paired. */
public record DiscoveredDevice(String name, String host, int port) {
}

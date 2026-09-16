package dev.andre.homecontrol.core;

/** A device seen on the network by one adapter, paired or not. */
public record DiscoveredDevice(String adapterId, String name, String host, int port) {
}

package dev.andre.homecontrol.core;

/** An adapter's discovery resolved a device on the network (new or changed). */
public record DeviceDiscoveredEvent(DiscoveredDevice device) {
}

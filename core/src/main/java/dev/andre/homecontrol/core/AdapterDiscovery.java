package dev.andre.homecontrol.core;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * How an adapter takes part in enrollment: the devices it sees on the network, and how its entry in a registered
 * device matches, moves and merges. Connecting and commands are {@link DeviceAdapter}'s; an adapter class implements
 * both, and one that sees nothing on the network implements only {@link DeviceAdapter}.
 */
public interface AdapterDiscovery {

    /** The same id as the adapter's {@link DeviceAdapter#id()}. */
    String id();

    /** Devices this adapter has seen on the network, paired or not. */
    List<DiscoveredDevice> discovered();

    /**
     * Settings for registering a discovered device without pairing, or empty when the device
     * must be paired first. Cast returns settings; Android TV returns empty.
     */
    default Optional<Map<String, String>> settingsFor(DiscoveredDevice found) {
        return Optional.empty();
    }

    /**
     * The address this adapter reaches the device at. Default: the device's own address; an
     * adapter whose entry can be merged in from another machine (Cast) remembers its own.
     */
    default String hostOf(Device device) {
        return device.host();
    }

    /** True when {@code device} already carries the discovered {@code found} through this adapter. */
    default boolean carries(Device device, DiscoveredDevice found) {
        return device.hasAdapter(id()) && hostOf(device).equalsIgnoreCase(found.host());
    }

    /**
     * True when this adapter stores credentials under the device id (Android TV: the certificate
     * alias IS the id), so its entry must never move to a device with another id.
     */
    default boolean credentialsBoundToDeviceId() {
        return false;
    }

    /**
     * True when this adapter's entry keeps the address it reaches the device at in its own settings (Cast), so it can
     * belong to a device at another address: matched by name, or merged into one by hand. Default false: the adapter
     * dials the device's own address, so its entry only works in a device at the address it was found at.
     */
    default boolean remembersOwnHost() {
        return false;
    }
}

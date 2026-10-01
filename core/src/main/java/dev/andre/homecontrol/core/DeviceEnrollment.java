package dev.andre.homecontrol.core;

import java.util.List;
import java.util.Map;

/**
 * The only way devices are added, merged, split or forgotten. Each change is saved to the registry and the devices it
 * touched are reconnected.
 */
public interface DeviceEnrollment {

    /**
     * Registers a freshly paired device and brings it up. Adapters the registry already has for this id (a Cast entry
     * on a re-paired Shield) are kept, and pairing-free receivers seen at the same address or under the same name are
     * merged in.
     */
    void adopt(Device device);

    /**
     * For prompt-paired adapters (webOS, Tizen): adds {@code adapterId} with {@code settings} to the registered device
     * at {@code host}, or registers a new device there, and (re)connects it. Like {@link #adopt}, it merges in the
     * pairing-free receivers at that address as well.
     */
    Device attach(String host, String name, DeviceKind kind, String adapterId, Map<String, String> settings);

    /** The setup page's "Add": merge into the matching device, or register a new one. */
    Device addDiscovered(String adapterId, String host, int port);

    /**
     * Lets each of the device's adapters forget its pairing, removes the device, and then disconnects it. If any of that
     * fails, the device stays registered and connected. An unknown id is a no-op.
     */
    void forget(String id);

    /** Moves every adapter of {@code source} into {@code target} and removes {@code source}. */
    Device merge(String targetId, String sourceId);

    /** Moves one adapter out of a device into a new device of its own. */
    Device split(String id, String adapterId);

    /** Discovered devices that need the pairing flow. */
    List<DiscoveredDevice> pairable();

    /** Pairing-free devices on the network that no registered device carries yet. */
    List<DiscoveredDevice> addable();
}

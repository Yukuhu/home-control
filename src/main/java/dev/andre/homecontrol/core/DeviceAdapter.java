package dev.andre.homecontrol.core;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The only thing that speaks a device protocol. One bean per protocol family; a
 * {@link Device} lists the adapter ids that apply to it with per-adapter settings.
 */
public interface DeviceAdapter {

    /** Stable key used in {@code Device.adapters} and in registry files, e.g. {@code androidtv}. */
    String id();

    Set<Capability> capabilities(Device device);

    /**
     * Brings the device up and returns immediately. {@code onChange} is called with every state
     * transition, including the first; the handle keeps reconnecting until closed.
     */
    DeviceHandle connect(Device device, Consumer<DeviceState> onChange);

    /** Removes credentials this adapter stored for the device. Default: nothing to remove. */
    default void forget(Device device) {
    }

    /** Devices this adapter has seen on the network, paired or not. */
    List<DiscoveredDevice> discovered();
}

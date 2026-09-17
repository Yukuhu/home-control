package dev.andre.homecontrol.core;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The only thing that speaks a device protocol. One bean per protocol family; a
 * {@link Device} lists the adapter ids that apply to it with per-adapter settings.
 */
public interface DeviceAdapter {

    /** Stable key used in {@code Device.adapters} and in registry files, e.g. {@code androidtv}. */
    String id();

    /** The family a device created by this adapter alone belongs to. */
    DeviceKind kind();

    Set<Capability> capabilities(Device device);

    /**
     * Brings the device up and returns immediately. {@code onChange} is called with every state
     * transition, including the first; the handle keeps reconnecting until closed.
     */
    DeviceHandle connect(Device device, Consumer<DeviceState> onChange);

    /**
     * What the device manager calls: like {@link #connect(Device, Consumer)}, for adapters whose
     * handles learn settings while connected and store them through {@code learned}. Default:
     * ignores {@code learned}.
     */
    default DeviceHandle connect(Device device, Consumer<DeviceState> onChange, LearnedSettings learned) {
        return connect(device, onChange);
    }

    /** Whether and how this adapter reports the foreground app; the deep-link test words its answer by it. */
    default ForegroundAppReporting foregroundAppReporting(Device device) {
        return ForegroundAppReporting.NONE;
    }

    /** Removes credentials this adapter stored for the device. Default: nothing to remove. */
    default void forget(Device device) {
    }

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
}

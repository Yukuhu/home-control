package dev.andre.homecontrol.core;

import java.util.Set;
import java.util.function.Consumer;

/**
 * The only thing that speaks a device protocol. One bean per protocol family; a
 * {@link Device} lists the adapter ids that apply to it with per-adapter settings. How the adapter finds devices and
 * how its entries merge is {@link AdapterDiscovery}'s.
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
     * What the device package calls: like {@link #connect(Device, Consumer)}, for adapters whose
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

    /**
     * Checks this adapter's settings of a registered device at startup. Throws {@link IllegalArgumentException} with
     * the reason when they cannot work. Default: nothing to check.
     */
    default void validate(Device device) {
    }

    /**
     * Brings this adapter's settings of a registered device up to date at startup, for example by moving a credential
     * out of the registry. Startup saves a changed result. Must be idempotent. Default: unchanged.
     */
    default Device migrate(Device device) {
        return device;
    }

}

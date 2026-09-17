package dev.andre.homecontrol.core;

import java.util.Map;

/**
 * Where a handle stores what it learns about its device while connected — a TV's client key, its
 * MAC address — without writing the {@link DeviceRegistry} itself. The device manager binds one to
 * each handle's device and adapter, so every registry write stays under its single lock.
 */
@FunctionalInterface
public interface LearnedSettings {

    /** Stores nothing: for handles connected outside the device manager (tests, probes). */
    LearnedSettings DISCARD = updates -> { };

    /**
     * Merges {@code updates} into the adapter's settings of the device. A no-op once the device was
     * forgotten or lost this adapter; a hand-entered MAC address is never replaced.
     */
    void store(Map<String, String> updates);
}

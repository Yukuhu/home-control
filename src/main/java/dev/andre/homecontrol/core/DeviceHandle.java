package dev.andre.homecontrol.core;

import dev.andre.homecontrol.device.DeviceState;

/** One live connection to one device through one adapter. Reconnects on its own until closed. */
public interface DeviceHandle extends AutoCloseable {

    DeviceState state();

    /**
     * Sends the action now or throws: {@link dev.andre.homecontrol.device.DeviceOfflineException}
     * when not connected, {@link UnsupportedActionException} when this adapter cannot do it.
     * Nothing is queued (global constraint).
     */
    void execute(Action action);

    @Override
    void close();
}

package dev.andre.homecontrol.core;

/** One live connection to one device through one adapter. Reconnects on its own until closed. */
public interface DeviceHandle extends AutoCloseable {

    DeviceState state();

    /**
     * Sends the action now or throws: {@link DeviceOfflineException}
     * when not connected, {@link UnsupportedActionException} when this adapter cannot do it.
     * Nothing is queued (global constraint).
     */
    void execute(Action action);

    @Override
    void close();
}

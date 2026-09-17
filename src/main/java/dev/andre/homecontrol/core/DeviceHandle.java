package dev.andre.homecontrol.core;

import java.util.Map;

/** One live connection to one device through one adapter. Reconnects on its own until closed. */
public interface DeviceHandle extends AutoCloseable {

    DeviceState state();

    /**
     * Sends the action now or throws: {@link DeviceOfflineException}
     * when not connected, {@link UnsupportedActionException} when this adapter cannot do it.
     * Nothing is queued (global constraint).
     */
    void execute(Action action);

    /**
     * Asks a receiver app and returns its reply, now or never (same exceptions as {@link #execute}).
     * Only Cast connections can; every other connection is unsupported.
     */
    default Map<String, Object> query(CastAppQuery query) {
        throw new UnsupportedActionException("This connection cannot ask receiver apps");
    }

    @Override
    void close();
}

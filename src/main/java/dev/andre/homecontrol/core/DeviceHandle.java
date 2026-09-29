package dev.andre.homecontrol.core;

import java.util.Optional;

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
     * What this connection offers beyond commands, such as its inputs ({@link InputListing}), its speaker grouping
     * ({@link GroupListing}) or its receiver apps ({@link ReceiverApps}); empty when it offers none of that kind.
     */
    default <T> Optional<T> feature(Class<T> type) {
        return type.isInstance(this) ? Optional.of(type.cast(this)) : Optional.empty();
    }

    @Override
    void close();
}

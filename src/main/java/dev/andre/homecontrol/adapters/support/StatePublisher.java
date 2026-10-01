package dev.andre.homecontrol.adapters.support;

import dev.andre.homecontrol.core.DeviceState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * A device's state as its listener sees it. A state is published, and becomes {@link #current()}, only when it differs
 * from the last one in something the UI shows ({@link DeviceState#sameIgnoringTime}); nothing is published after
 * {@link #close()}. Publishing is serialized, so the listener sees the states in order; reading is not, so a slow
 * listener never holds up {@link #current()}. A listener's {@link RuntimeException} is logged and the session goes on;
 * an {@link Error} propagates.
 */
public final class StatePublisher {

    private static final Logger log = LoggerFactory.getLogger(StatePublisher.class);

    private final String deviceId;
    private final Consumer<DeviceState> listener;
    private final Object lock = new Object();
    // Immutable record, replaced wholesale under lock; readers need visibility only.
    @SuppressWarnings("java:S3077")
    private volatile DeviceState current;
    private boolean closed; // guarded by lock

    public StatePublisher(String deviceId, DeviceState initial, Consumer<DeviceState> listener) {
        this.deviceId = deviceId;
        this.listener = listener;
        this.current = initial;
    }

    public DeviceState current() {
        return current;
    }

    /** Applies {@code change} to the current state and publishes the result; returns the state now current. */
    public DeviceState update(UnaryOperator<DeviceState> change) {
        synchronized (lock) {
            publishLocked(change.apply(current));
            return current;
        }
    }

    public void publish(DeviceState next) {
        synchronized (lock) {
            publishLocked(next);
        }
    }

    /** Tells the listener the current state, whatever it was told before: a session's first report. */
    public void announce() {
        synchronized (lock) {
            if (!closed) {
                notifyListener(current);
            }
        }
    }

    public void close() {
        synchronized (lock) {
            closed = true;
        }
    }

    private void publishLocked(DeviceState next) {
        if (closed || next.sameIgnoringTime(current)) {
            return;
        }
        current = next;
        notifyListener(next);
    }

    private void notifyListener(DeviceState state) {
        try {
            listener.accept(state);
        } catch (RuntimeException e) {
            log.warn("A device state listener failed for {}", deviceId, e);
        }
    }
}

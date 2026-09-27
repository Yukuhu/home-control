package dev.andre.homecontrol.testsupport;

import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.DeviceStatus;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.awaitility.Awaitility.await;

/** Records every state a session publishes, in order, for tests that assert on what was published. */
public final class RecordingStateListener implements Consumer<DeviceState> {

    private final List<DeviceState> states = new CopyOnWriteArrayList<>();

    @Override
    public void accept(DeviceState state) {
        states.add(state);
    }

    /** Every state so far, oldest first. */
    public List<DeviceState> all() {
        return List.copyOf(states);
    }

    /** The newest state. Throws {@link java.util.NoSuchElementException} before the first one. */
    public DeviceState last() {
        return states.getLast();
    }

    public void clear() {
        states.clear();
    }

    /** {@link #awaitStatus(DeviceStatus, Duration)} with Awaitility's default of 10 s. */
    public DeviceState awaitStatus(DeviceStatus status) {
        return awaitStatus(status, Duration.ofSeconds(10));
    }

    /** Waits until some state so far has {@code status}, and returns the first that has. */
    public DeviceState awaitStatus(DeviceStatus status, Duration atMost) {
        await().atMost(atMost).until(() -> states.stream().anyMatch(state -> state.status() == status));
        return states.stream().filter(state -> state.status() == status).findFirst().orElseThrow();
    }
}

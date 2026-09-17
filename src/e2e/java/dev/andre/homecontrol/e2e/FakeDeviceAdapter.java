package dev.andre.homecontrol.e2e;

import dev.andre.homecontrol.core.Action;
import dev.andre.homecontrol.core.ActionFailedException;
import dev.andre.homecontrol.core.Capability;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceAdapter;
import dev.andre.homecontrol.core.DeviceHandle;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.DeviceStatus;
import dev.andre.homecontrol.core.DiscoveredDevice;
import dev.andre.homecontrol.core.KeyPress;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * A {@link DeviceAdapter} double for the browser tests: no protocol, just recorded actions and a
 * controllable state/failure per device, driven entirely by the {@code caps}/{@code fail}
 * adapter settings a device is adopted with (see {@link E2eApplicationTest#adopt}).
 */
public class FakeDeviceAdapter implements DeviceAdapter {

    public record Recorded(String deviceId, Action action) {
    }

    private final List<Recorded> recorded = new CopyOnWriteArrayList<>();
    private final Map<String, Consumer<DeviceState>> listeners = new ConcurrentHashMap<>();
    /** One-shot artificial round-trip delay for the next {@code PressKey} carrying this press type. */
    private final Map<KeyPress, Duration> pressDelays = new ConcurrentHashMap<>();

    @Override
    public String id() {
        return "e2e-fake";
    }

    @Override
    public DeviceKind kind() {
        return DeviceKind.ANDROID_TV;
    }

    @Override
    public Set<Capability> capabilities(Device device) {
        Set<Capability> capabilities = new HashSet<>();
        for (String name : device.adapterSettings(id()).getOrDefault("caps", "").split(",")) {
            String trimmed = name.strip();
            if (!trimmed.isEmpty()) {
                capabilities.add(Capability.valueOf(trimmed));
            }
        }
        return capabilities;
    }

    @Override
    public DeviceHandle connect(Device device, Consumer<DeviceState> onChange) {
        listeners.put(device.id(), onChange);
        Set<String> failing = new HashSet<>();
        for (String name : device.adapterSettings(id()).getOrDefault("fail", "").split(",")) {
            String trimmed = name.strip();
            if (!trimmed.isEmpty()) {
                failing.add(trimmed);
            }
        }
        DeviceState initial = new DeviceState(DeviceStatus.CONNECTED, true, "com.example.launcher",
                0, 0, false, Instant.now());
        onChange.accept(initial);
        return new FakeHandle(device, failing, initial);
    }

    @Override
    public List<DiscoveredDevice> discovered() {
        return List.of();
    }

    public List<Recorded> recorded() {
        return List.copyOf(recorded);
    }

    public List<Action> recorded(String deviceId) {
        return recorded.stream().filter(r -> r.deviceId().equals(deviceId)).map(Recorded::action).toList();
    }

    public void clear() {
        recorded.clear();
    }

    /** Publishes {@code state} through the {@code onChange} callback the device last connected with. */
    public void push(String deviceId, DeviceState state) {
        Consumer<DeviceState> listener = listeners.get(deviceId);
        if (listener != null) {
            listener.accept(state);
        }
    }

    /**
     * Makes the next {@code PressKey} carrying {@code press} block the request thread for
     * {@code duration} before it is recorded, simulating a slow round trip to the device for
     * that one key — server-side, so it does not race Playwright's own request-routing (which
     * serializes callback delivery for concurrent requests on one page, masking client-side
     * ordering bugs rather than reproducing them).
     */
    public void delayNextPress(KeyPress press, Duration duration) {
        pressDelays.put(press, duration);
    }

    private final class FakeHandle implements DeviceHandle {
        private final Device device;
        private final Set<String> failing;
        private final DeviceState state;

        FakeHandle(Device device, Set<String> failing, DeviceState state) {
            this.device = device;
            this.failing = failing;
            this.state = state;
        }

        @Override
        public DeviceState state() {
            return state;
        }

        @Override
        public void execute(Action action) {
            if (action instanceof Action.PressKey pressed) {
                Duration delay = pressDelays.remove(pressed.press());
                if (delay != null) {
                    try {
                        Thread.sleep(delay.toMillis());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
            recorded.add(new Recorded(device.id(), action));
            String name = action.getClass().getSimpleName();
            if (failing.contains(name)) {
                throw new ActionFailedException(device.name() + " refused to " + name);
            }
        }

        @Override
        public void close() {
            listeners.remove(device.id());
        }
    }
}

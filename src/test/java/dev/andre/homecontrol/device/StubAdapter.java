package dev.andre.homecontrol.device;

import dev.andre.homecontrol.core.Action;
import dev.andre.homecontrol.core.CastAppQuery;
import dev.andre.homecontrol.core.Capability;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceAdapter;
import dev.andre.homecontrol.core.DeviceHandle;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.DeviceStatus;
import dev.andre.homecontrol.core.DiscoveredDevice;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** A scriptable adapter for DeviceManager tests: no network, records what it was asked to do. */
class StubAdapter implements DeviceAdapter {

    private final String id;
    private final DeviceKind kind;
    private final boolean pairingFree;
    private final boolean boundCredentials;
    private final Set<Capability> capabilities;

    final List<DiscoveredDevice> visible = new CopyOnWriteArrayList<>();
    final List<String> forgotten = new CopyOnWriteArrayList<>();
    /** Latest handle per device id. */
    final Map<String, StubHandle> handles = new ConcurrentHashMap<>();

    StubAdapter(String id, DeviceKind kind, boolean pairingFree, boolean boundCredentials, Capability... capabilities) {
        this.id = id;
        this.kind = kind;
        this.pairingFree = pairingFree;
        this.boundCredentials = boundCredentials;
        this.capabilities = EnumSet.noneOf(Capability.class);
        this.capabilities.addAll(List.of(capabilities));
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public DeviceKind kind() {
        return kind;
    }

    @Override
    public Set<Capability> capabilities(Device device) {
        Set<Capability> copy = EnumSet.noneOf(Capability.class);
        copy.addAll(capabilities);
        return copy;
    }

    @Override
    public DeviceHandle connect(Device device, Consumer<DeviceState> onChange) {
        StubHandle handle = new StubHandle(onChange);
        handles.put(device.id(), handle);
        handle.report(DeviceState.initial().withStatus(DeviceStatus.CONNECTED));
        return handle;
    }

    @Override
    public void forget(Device device) {
        forgotten.add(device.id());
    }

    @Override
    public List<DiscoveredDevice> discovered() {
        return List.copyOf(visible);
    }

    @Override
    public Optional<Map<String, String>> settingsFor(DiscoveredDevice found) {
        if (!pairingFree || !id.equals(found.adapterId())) {
            return Optional.empty();
        }
        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("host", found.host());
        settings.put("port", String.valueOf(found.port()));
        String stableId = found.attributes().get("id");
        if (stableId != null) {
            settings.put("stableId", stableId);
        }
        return Optional.of(settings);
    }

    /** Like Cast: a pairing-free entry remembers its own address, which may differ from the device's. */
    @Override
    public String hostOf(Device device) {
        return device.adapterSettings(id).getOrDefault("host", device.host());
    }

    /**
     * Like Cast: a stored {@code stableId} survives an address change, so a registered device
     * keeps carrying the receiver even after it moves; otherwise falls back to the address.
     */
    @Override
    public boolean carries(Device device, DiscoveredDevice found) {
        if (!device.hasAdapter(id)) {
            return false;
        }
        String storedId = device.adapterSettings(id).get("stableId");
        String foundId = found.attributes().get("id");
        return (storedId != null && storedId.equals(foundId)) || hostOf(device).equalsIgnoreCase(found.host());
    }

    @Override
    public boolean credentialsBoundToDeviceId() {
        return boundCredentials;
    }

    static final class StubHandle implements DeviceHandle {

        private final Consumer<DeviceState> onChange;
        final List<Action> executed = new CopyOnWriteArrayList<>();
        final List<CastAppQuery> queried = new CopyOnWriteArrayList<>();
        /** What {@link #query} answers; null means this connection cannot ask receiver apps. */
        volatile Map<String, Object> answer;
        volatile DeviceState state = DeviceState.initial();
        volatile RuntimeException failure;
        volatile boolean closed;

        StubHandle(Consumer<DeviceState> onChange) {
            this.onChange = onChange;
        }

        void report(DeviceState updated) {
            state = updated;
            onChange.accept(updated);
        }

        @Override
        public DeviceState state() {
            return state;
        }

        @Override
        public void execute(Action action) {
            if (failure != null) {
                throw failure;
            }
            executed.add(action);
        }

        @Override
        public Map<String, Object> query(CastAppQuery query) {
            if (failure != null) {
                throw failure;
            }
            queried.add(query);
            if (answer == null) {
                return DeviceHandle.super.query(query);
            }
            return answer;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}

package dev.andre.homecontrol.device;

import dev.andre.homecontrol.adapters.androidtv.AndroidTvAdapter;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceHandle;
import dev.andre.homecontrol.core.DeviceRegistry;
import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.DeviceStateChangedEvent;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns one {@link DeviceHandle} per registered device. The v1 UI drives whichever
 * device is first in the registry; the map is what makes "multiple devices later"
 * a UI change rather than a rewrite.
 */
@Service
public class DeviceSessionManager implements AutoCloseable {

    private final Map<String, DeviceHandle> sessions = new ConcurrentHashMap<>();

    private final DeviceRegistry registry;
    private final AndroidTvAdapter adapter;
    private final ApplicationEventPublisher events;

    public DeviceSessionManager(DeviceRegistry registry, AndroidTvAdapter adapter,
                                ApplicationEventPublisher events) {
        this.registry = registry;
        this.adapter = adapter;
        this.events = events;
    }

    @PostConstruct
    public void startRegisteredDevices() {
        // Only the active device, not every entry. A re-pair at a changed address leaves a
        // stale entry behind (the id is derived from the host, spec §6), and since
        // DeviceStateChangedEvent carries no device id, a second session's DISCONNECTED
        // events would reach every tab and overwrite the live device's badge. The v1 UI
        // drives first() and nothing else.
        registry.first().ifPresent(this::startSession);
    }

    public Optional<DeviceHandle> active() {
        return registry.first().map(device -> sessions.get(device.id()));
    }

    public DeviceState state() {
        Optional<Device> device = registry.first();
        if (device.isEmpty()) {
            return DeviceState.initial();
        }
        DeviceHandle handle = sessions.get(device.get().id());
        return handle == null ? DeviceState.unpaired() : handle.state();
    }

    public Optional<Device> activeDevice() {
        return registry.first();
    }

    /** Registers a freshly paired device and brings its session up. */
    public void adopt(Device device) {
        registry.save(device);
        startSession(device);
    }

    public void forget(String id) {
        Optional<Device> registered = registry.findById(id);
        if (registered.isEmpty()) {
            return;
        }
        Device device = registered.get();
        DeviceHandle handle = sessions.remove(device.id());
        if (handle != null) {
            handle.close();
        }
        registry.delete(device.id());
        adapter.forget(device);
        events.publishEvent(new DeviceStateChangedEvent(state()));
    }

    private void startSession(Device device) {
        DeviceHandle existing = sessions.remove(device.id());
        if (existing != null) {
            existing.close();
        }

        DeviceHandle handle = adapter.connect(device,
                state -> events.publishEvent(new DeviceStateChangedEvent(state)));
        sessions.put(device.id(), handle);
    }

    @Override
    @PreDestroy
    public void close() {
        sessions.values().forEach(DeviceHandle::close);
        sessions.clear();
    }
}

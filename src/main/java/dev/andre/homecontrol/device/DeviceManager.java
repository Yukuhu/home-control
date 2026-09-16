package dev.andre.homecontrol.device;

import dev.andre.homecontrol.core.Action;
import dev.andre.homecontrol.core.Capability;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceAdapter;
import dev.andre.homecontrol.core.DeviceHandle;
import dev.andre.homecontrol.core.DeviceOfflineException;
import dev.andre.homecontrol.core.DeviceRegistry;
import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.DeviceStateChangedEvent;
import dev.andre.homecontrol.core.DiscoveredDevice;
import dev.andre.homecontrol.core.UnsupportedActionException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns one live {@link DeviceHandle} per registered device and adapter, and is the only
 * thing the web layer talks to about devices. Every device gets a handle now that
 * {@link DeviceStateChangedEvent} carries the device id — the v1 "active device only" rule
 * existed solely because it did not.
 */
@Service
public class DeviceManager implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DeviceManager.class);

    private final DeviceRegistry registry;
    private final Map<String, DeviceAdapter> adapters = new LinkedHashMap<>();
    private final ApplicationEventPublisher events;
    /** device id → (adapter id → handle), in the device's adapter order. */
    private final Map<String, Map<String, DeviceHandle>> handles = new ConcurrentHashMap<>();
    /**
     * Guards every write to {@link #handles} (via {@link #connect}, {@link #closeHandles},
     * {@link #adopt}, {@link #forget} and {@link #close}) so that an adopt racing another
     * adopt, or an adopt racing a forget, can never leave two live handles for one device or
     * a live handle for a device that {@link #forget} just deleted from the registry.
     * {@code adapter.connect} returns immediately (it never blocks), so holding this while
     * calling it is safe. {@link #state}, {@link #states}, {@link #capabilities} and
     * {@link #execute} read {@link #handles} without it — they only ever see either the old
     * or the new value, both valid, so lock-free reads cost nothing here.
     */
    private final Object lock = new Object();

    public DeviceManager(DeviceRegistry registry, List<DeviceAdapter> adapters,
                         ApplicationEventPublisher events) {
        this.registry = registry;
        adapters.forEach(adapter -> this.adapters.put(adapter.id(), adapter));
        this.events = events;
    }

    @PostConstruct
    public void start() {
        registry.findAll().forEach(this::connect);
    }

    public List<Device> devices() {
        return registry.findAll().stream()
                .sorted(Comparator.comparing(Device::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    public Optional<Device> device(String id) {
        return registry.findById(id);
    }

    /** The most recently paired device: what {@code /} shows when no device is selected. */
    public Optional<Device> defaultDevice() {
        return registry.first();
    }

    /** The primary adapter's state; an unknown or handle-less device reads as DISCONNECTED. */
    public DeviceState state(String id) {
        Map<String, DeviceHandle> deviceHandles = handles.get(id);
        if (deviceHandles == null || deviceHandles.isEmpty()) {
            return DeviceState.initial();
        }
        return deviceHandles.values().iterator().next().state();
    }

    public Map<String, DeviceState> states() {
        Map<String, DeviceState> states = new LinkedHashMap<>();
        devices().forEach(device -> states.put(device.id(), state(device.id())));
        return states;
    }

    public Set<Capability> capabilities(String id) {
        Set<Capability> capabilities = EnumSet.noneOf(Capability.class);
        registry.findById(id).ifPresent(device -> device.adapters().keySet().forEach(adapterId -> {
            DeviceAdapter adapter = adapters.get(adapterId);
            if (adapter != null) {
                capabilities.addAll(adapter.capabilities(device));
            }
        }));
        return capabilities;
    }

    /**
     * Sends through the first of the device's adapters that declares the needed capability.
     * A capability the device's adapters declare but currently have no live handle for (not
     * yet connected, or a failed connect) is offline, not unsupported — only a capability
     * none of the device's adapters ever declare is rejected as unsupported.
     */
    public void execute(String id, Action action) {
        Device device = registry.findById(id)
                .orElseThrow(() -> new DeviceOfflineException("No device with id " + id));
        Map<String, DeviceHandle> deviceHandles = handles.getOrDefault(id, Map.of());
        boolean capabilityKnown = false;
        for (String adapterId : device.adapters().keySet()) {
            DeviceAdapter adapter = adapters.get(adapterId);
            if (adapter == null || !adapter.capabilities(device).contains(action.requires())) {
                continue;
            }
            capabilityKnown = true;
            DeviceHandle handle = deviceHandles.get(adapterId);
            if (handle != null) {
                handle.execute(action);
                return;
            }
        }
        if (capabilityKnown) {
            throw new DeviceOfflineException(device.name() + " is not connected");
        }
        throw new UnsupportedActionException(device.name() + " cannot perform " + action);
    }

    /** Registers a freshly paired device and brings it up. */
    public void adopt(Device device) {
        synchronized (lock) {
            registry.save(device);
            connect(device);
        }
    }

    public void forget(String id) {
        synchronized (lock) {
            Optional<Device> registered = registry.findById(id);
            if (registered.isEmpty()) {
                return;
            }
            Device device = registered.get();
            closeHandles(id);
            device.adapters().keySet().forEach(adapterId -> {
                DeviceAdapter adapter = adapters.get(adapterId);
                if (adapter != null) {
                    adapter.forget(device);
                }
            });
            registry.delete(id);
        }
        events.publishEvent(new DeviceStateChangedEvent(id, DeviceState.initial()));
    }

    public List<DiscoveredDevice> discovered() {
        return adapters.values().stream().flatMap(adapter -> adapter.discovered().stream()).toList();
    }

    /**
     * Connects every one of the device's adapters, under {@link #lock} so this can never
     * interleave with another {@link #connect}/{@link #closeHandles} for the same or a
     * different device. A failing adapter never leaves the device half-connected: its
     * {@link RuntimeException} is caught and logged, whatever handles this call already
     * opened for the device are closed, and the device is left with no handles at all —
     * {@link #state} then reads it as DISCONNECTED — rather than failing {@link #start} and
     * leaking those handles, or every other device's connect along with it.
     */
    private void connect(Device device) {
        synchronized (lock) {
            closeHandles(device.id());
            Map<String, DeviceHandle> deviceHandles = new LinkedHashMap<>();
            for (String adapterId : device.adapters().keySet()) {
                DeviceAdapter adapter = adapters.get(adapterId);
                if (adapter == null) {
                    continue;
                }
                try {
                    deviceHandles.put(adapterId, adapter.connect(device,
                            state -> events.publishEvent(new DeviceStateChangedEvent(device.id(), state))));
                } catch (RuntimeException e) {
                    log.warn("Could not connect {} via the {} adapter; leaving it disconnected",
                            device.id(), adapterId, e);
                    deviceHandles.values().forEach(DeviceHandle::close);
                    return;
                }
            }
            handles.put(device.id(), deviceHandles);
        }
    }

    private void closeHandles(String id) {
        synchronized (lock) {
            Map<String, DeviceHandle> existing = handles.remove(id);
            if (existing != null) {
                existing.values().forEach(DeviceHandle::close);
            }
        }
    }

    @Override
    @PreDestroy
    public void close() {
        synchronized (lock) {
            handles.keySet().forEach(this::closeHandles);
        }
    }
}

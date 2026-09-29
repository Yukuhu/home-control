package dev.andre.homecontrol.device;

import dev.andre.homecontrol.core.Action;
import dev.andre.homecontrol.core.CastAppQuery;
import dev.andre.homecontrol.core.Capability;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceAdapter;
import dev.andre.homecontrol.core.DeviceDiscoveredEvent;
import dev.andre.homecontrol.core.DeviceHandle;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.core.DeviceRegistry;
import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.DeviceStateChangedEvent;
import dev.andre.homecontrol.core.DiscoveredDevice;
import dev.andre.homecontrol.core.ForegroundAppReporting;
import dev.andre.homecontrol.core.Hosts;
import dev.andre.homecontrol.core.SpeakerTopology;
import dev.andre.homecontrol.core.TvInput;
import dev.andre.homecontrol.storage.DataDirectory;
import dev.andre.homecontrol.storage.StorageException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Owns one live {@link DeviceHandle} per registered device and adapter, composes their states
 * into one per device, and is the only place devices are added, merged, split or forgotten.
 * It is the only thing the web layer talks to about devices. Every device gets a handle now that
 * {@link DeviceStateChangedEvent} carries the device id — the v1 "active device only" rule
 * existed solely because it did not.
 */
@Service
public class DeviceManager implements AutoCloseable {

    private static final String NO_DEVICE_PREFIX = "No device with id ";

    private static final Logger log = LoggerFactory.getLogger(DeviceManager.class);

    private final DeviceRegistry registry;
    private final Map<String, DeviceAdapter> adapters = new LinkedHashMap<>();
    private final ApplicationEventPublisher events;
    private final RegistryLock lock = new RegistryLock();
    private final AdapterSettingsStore settings;
    private final DeviceConnections connections;
    private final RegisteredDevices queries;
    private final CommandRouter commands;

    public DeviceManager(DeviceRegistry registry, List<DeviceAdapter> adapters,
                         ApplicationEventPublisher events) {
        this.registry = registry;
        adapters.forEach(adapter -> this.adapters.put(adapter.id(), adapter));
        this.events = events;
        this.settings = new AdapterSettingsStore(registry, this.adapters, lock);
        this.connections = new DeviceConnections(this.adapters, events, settings::updateAdapterSettings);
        this.queries = new RegisteredDevices(registry, this.adapters, connections);
        this.commands = new CommandRouter(registry, this.adapters, connections);
    }

    /**
     * Checks every entry of a running adapter before anything connects, so a bad one stops startup. Then brings each
     * device's settings up to date and connects it. An entry of a switched-off module is left as it is until its
     * module is on.
     */
    @PostConstruct
    public void start() {
        List<Device> registered = registry.findAll();
        registered.forEach(this::validate);
        registered.forEach(device -> connect(migrate(device)));
    }

    private void validate(Device device) {
        for (String adapterId : device.adapters().keySet()) {
            DeviceAdapter adapter = adapters.get(adapterId);
            if (adapter == null) {
                continue;
            }
            try {
                adapter.validate(device);
            } catch (IllegalArgumentException e) {
                throw new StorageException("Invalid device record " + device.id() + " in " + DataDirectory.DEVICES
                        + ": " + e.getMessage() + "; fix or delete it", e);
            }
        }
    }

    private Device migrate(Device device) {
        synchronized (lock) {
            Device migrated = device;
            for (String adapterId : device.adapters().keySet()) {
                DeviceAdapter adapter = adapters.get(adapterId);
                if (adapter != null) {
                    migrated = adapter.migrate(migrated);
                }
            }
            if (!migrated.equals(device)) {
                registry.save(migrated);
            }
            return migrated;
        }
    }

    public List<Device> devices() {
        return queries.devices();
    }

    public Optional<Device> device(String id) {
        return queries.device(id);
    }

    /** The most recently paired device: what {@code /} shows when no device is selected. */
    public Optional<Device> defaultDevice() {
        return queries.defaultDevice();
    }

    /** The composed state of the device's adapters; an unknown or handle-less device reads as DISCONNECTED. */
    public DeviceState state(String id) {
        return queries.state(id);
    }

    public Map<String, DeviceState> states() {
        return queries.states();
    }

    public Set<Capability> capabilities(String id) {
        return queries.capabilities(id);
    }

    /** Sends through the first of the device's adapters that can; see {@link CommandRouter#execute}. */
    public void execute(String id, Action action) {
        commands.execute(id, action);
    }

    /** Asks the first Cast receiver adapter that can answer; see {@link CommandRouter#query}. */
    public Map<String, Object> query(String id, CastAppQuery query) {
        return commands.query(id, query);
    }

    /** Whether the adapter's module is switched on: a device's entry for it in devices.json does not say so. */
    public boolean adapterEnabled(String adapterId) {
        return queries.adapterEnabled(adapterId);
    }

    /**
     * Registers a freshly paired device and brings it up. Adapters the registry already has for
     * this id (a Cast entry on a re-paired Shield) are kept, and pairing-free receivers seen at
     * the same address or under the same name are merged in.
     */
    public void adopt(Device device) {
        synchronized (lock) {
            Device adopted = registry.findById(device.id())
                    .map(existing -> DeviceMatching.keepOtherAdapters(existing, device))
                    .orElse(device);
            adopted = absorbAddable(adopted);
            registry.save(adopted);
            connect(adopted);
        }
    }

    public void forget(String id) {
        synchronized (lock) {
            Optional<Device> registered = registry.findById(id);
            if (registered.isEmpty()) {
                return;
            }
            Device device = registered.get();
            connections.end(id).forEach(DeviceHandle::close);
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

    /**
     * For prompt-paired adapters (webOS, Tizen): adds {@code adapterId} with {@code settings} to the
     * registered device at {@code host}, or registers a new device there, and (re)connects it.
     * Goes through {@link #adopt}, so pairing-free receivers at that address are absorbed as well.
     */
    public Device attach(String host, String name, DeviceKind kind, String adapterId, Map<String, String> settings) {
        Device merged;
        synchronized (lock) {
            merged = DeviceMatching.attach(registry.findAll(), host, name, kind, adapterId, settings,
                    Instant.now(), Hosts::same);
            adopt(merged);
            merged = registry.findById(merged.id()).orElse(merged);
        }
        return merged;
    }

    /** What a handle learned while connected; see {@link AdapterSettingsStore#updateAdapterSettings}. */
    public void updateAdapterSettings(String id, String adapterId, Map<String, String> updates) {
        settings.updateAdapterSettings(id, adapterId, updates);
    }

    /** The best foreground-app reporting among the device's adapters; {@code NONE} for an unknown id. */
    public ForegroundAppReporting foregroundAppReporting(String id) {
        return queries.foregroundAppReporting(id);
    }

    /** True when one of the device's adapters can switch it on with Wake-on-LAN. */
    public boolean wakesOnLan(String id) {
        return settings.wakesOnLan(id);
    }

    public Optional<String> wakeOnLanMac(String id) {
        return settings.wakeOnLanMac(id);
    }

    /** Stores or clears a hand-entered MAC; see {@link AdapterSettingsStore#setWakeOnLanMac}. */
    public void setWakeOnLanMac(String id, String mac) {
        settings.setWakeOnLanMac(id, mac);
    }

    /** Grouping as seen by the first of the device's handles that knows it; empty otherwise. */
    public Optional<SpeakerTopology> speakerTopology(String id) {
        return queries.speakerTopology(id);
    }

    /** Inputs from the first of the device's handles that lists any; empty when none does. */
    public List<TvInput> inputs(String id) {
        return queries.inputs(id);
    }

    public List<DiscoveredDevice> discovered() {
        return adapters.values().stream().flatMap(adapter -> adapter.discovered().stream()).toList();
    }

    /** Discovered devices that need the pairing flow. */
    public List<DiscoveredDevice> pairable() {
        return discovered().stream().filter(found -> !pairingFree(found)).toList();
    }

    /** Pairing-free devices on the network that no registered device carries yet. */
    public List<DiscoveredDevice> addable() {
        List<Device> registered = registry.findAll();
        return discovered().stream()
                .filter(this::pairingFree)
                .filter(found -> !isRegistered(registered, found))
                .toList();
    }

    /** The setup page's "Add": merge into the matching device, or register a new one. */
    public Device addDiscovered(String adapterId, String host, int port) {
        synchronized (lock) {
            DeviceAdapter adapter = adapters.get(adapterId);
            if (adapter == null) {
                throw new IllegalArgumentException("The " + adapterId + " module is switched off");
            }
            DiscoveredDevice found = adapter.discovered().stream()
                    .filter(candidate -> candidate.host().equalsIgnoreCase(host) && candidate.port() == port)
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("That device is no longer visible on the network"));
            Map<String, String> settings = adapter.settingsFor(found)
                    .orElseThrow(() -> new IllegalArgumentException(found.name() + " has to be paired, not added"));
            List<Device> registered = registry.findAll();
            if (isRegistered(registered, found)) {
                throw new IllegalArgumentException(found.name() + " is already added");
            }
            Device device = DeviceMatching.bestMatch(registered, found)
                    .map(target -> target.withAdapter(adapterId, settings))
                    .orElseGet(() -> new Device(DeviceMatching.uniqueId(registered, adapterId, found.host()), found.name(),
                            adapter.kind(), found.host(), Map.of(adapterId, settings), Instant.now()));
            registry.save(device);
            connect(device);
            return device;
        }
    }

    /**
     * Automatic merge (spec §5.1): only into an existing device, never creating one. A receiver
     * a registered device already carries by a stable identity (Cast's mDNS {@code id}) rather
     * than its address is re-pointed and reconnected when it answers at a new one — an adapter
     * matches {@link DeviceAdapter#carries} on identity alone, so without this the device would
     * otherwise keep dialling the stale address forever and the receiver could never be re-added.
     */
    @EventListener
    public void onDiscovered(DeviceDiscoveredEvent event) {
        DiscoveredDevice found = event.device();
        DeviceAdapter adapter = adapters.get(found.adapterId());
        if (adapter == null) {
            return;
        }
        Optional<Map<String, String>> settings = adapter.settingsFor(found);
        if (settings.isEmpty()) {
            return;
        }
        synchronized (lock) {
            List<Device> registered = registry.findAll();
            Optional<Device> carrier = registered.stream().filter(device -> adapter.carries(device, found)).findFirst();
            if (carrier.isPresent()) {
                reconnectIfMoved(carrier.get(), found.adapterId(), settings.get());
                return;
            }
            DeviceMatching.bestMatch(registered, found).ifPresent(target -> {
                Device merged = target.withAdapter(found.adapterId(), settings.get());
                registry.save(merged);
                connect(merged);
                log.info("Merged {} receiver {} into {}", found.adapterId(), found.name(), target.name());
            });
        }
    }

    /**
     * Re-points a device's adapter entry to a receiver's new address once mDNS says it moved,
     * and reconnects it there. A no-op when the host and port the adapter already stored still
     * match, so a routine re-announcement at the same address never tears the connection down.
     * Must run under {@link #lock}.
     */
    private void reconnectIfMoved(Device device, String adapterId, Map<String, String> newSettings) {
        Map<String, String> current = device.adapterSettings(adapterId);
        if (Objects.equals(current.get("host"), newSettings.get("host"))
                && Objects.equals(current.get("port"), newSettings.get("port"))) {
            return;
        }
        Device moved = device.withAdapter(adapterId, newSettings);
        registry.save(moved);
        connect(moved);
        log.info("{} receiver for {} answered at a new address; reconnecting", adapterId, device.name());
    }

    /**
     * Receivers resolved while the context was still starting were published before
     * {@link #onDiscovered} was registered as a listener; {@link DiscoveryCatchUp} gives them the
     * same automatic merge once the application is ready.
     */
    public void mergeVisibleReceivers() {
        addable().forEach(found -> onDiscovered(new DeviceDiscoveredEvent(found)));
    }

    /** Moves every adapter of {@code source} into {@code target} and removes {@code source}. */
    public Device merge(String targetId, String sourceId) {
        if (targetId.equals(sourceId)) {
            throw new IllegalArgumentException("Pick two different devices to merge");
        }
        Device merged;
        synchronized (lock) {
            Device target = registry.findById(targetId)
                    .orElseThrow(() -> new IllegalArgumentException(NO_DEVICE_PREFIX + targetId));
            Device source = registry.findById(sourceId)
                    .orElseThrow(() -> new IllegalArgumentException(NO_DEVICE_PREFIX + sourceId));
            merged = target;
            for (Map.Entry<String, Map<String, String>> entry : source.adapters().entrySet()) {
                String adapterId = entry.getKey();
                if (target.hasAdapter(adapterId)) {
                    throw new IllegalArgumentException(target.name() + " already has a " + adapterId + " connection");
                }
                DeviceAdapter adapter = adapters.get(adapterId);
                if (adapter == null) {
                    // Its pairing may be bound to the source's id (Android TV's is), so it stays where it is.
                    throw new IllegalArgumentException("The " + adapterId + " module is switched off");
                }
                if (adapter.credentialsBoundToDeviceId()) {
                    throw new IllegalArgumentException("Merge the other way round: the " + adapterId
                            + " pairing of " + source.name() + " only works under its own id");
                }
                merged = merged.withAdapter(adapterId, entry.getValue());
            }
            // Credentials move with the settings, so the source is removed WITHOUT adapter.forget().
            connections.end(sourceId).forEach(DeviceHandle::close);
            registry.delete(sourceId);
            registry.save(merged);
            connect(merged);
        }
        events.publishEvent(new DeviceStateChangedEvent(sourceId, DeviceState.initial()));
        return merged;
    }

    /** Moves one adapter out of a device into a new device of its own. */
    public Device split(String id, String adapterId) {
        synchronized (lock) {
            Device device = registry.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException(NO_DEVICE_PREFIX + id));
            if (!device.hasAdapter(adapterId)) {
                throw new IllegalArgumentException(device.name() + " has no " + adapterId + " connection");
            }
            if (device.adapters().size() < 2) {
                throw new IllegalArgumentException(device.name() + " has only one connection; there is nothing to split");
            }
            DeviceAdapter adapter = adapters.get(adapterId);
            if (adapter == null) {
                throw new IllegalArgumentException("The " + adapterId + " module is switched off");
            }
            if (adapter.credentialsBoundToDeviceId()) {
                throw new IllegalArgumentException("The " + adapterId + " pairing belongs to " + device.name()
                        + " and cannot be split off; split the other connections instead");
            }
            Map<String, Map<String, String>> remaining = new LinkedHashMap<>(device.adapters());
            remaining.remove(adapterId);
            Device rest = new Device(device.id(), device.name(), device.kind(), device.host(), remaining, device.lastSeen());
            // A receiver merged in from another address takes that address with it.
            String host = adapter.hostOf(device);
            Device split = new Device(DeviceMatching.uniqueId(registry.findAll(), adapterId, host),
                    device.name() + " (" + adapterId + ")",
                    adapter.kind(), host,
                    Map.of(adapterId, device.adapterSettings(adapterId)), device.lastSeen());
            registry.save(rest);
            registry.save(split);
            connect(rest);
            connect(split);
            return split;
        }
    }

    private boolean pairingFree(DiscoveredDevice found) {
        DeviceAdapter adapter = adapters.get(found.adapterId());
        return adapter != null && adapter.settingsFor(found).isPresent();
    }

    /**
     * Registered once any device carries it through its adapter — judged by the adapter, at
     * the address (or identity) that adapter entry remembers, not the device's own address.
     * That includes a device split off earlier, which is why a split is never merged back
     * automatically.
     */
    private boolean isRegistered(List<Device> registered, DiscoveredDevice found) {
        DeviceAdapter adapter = adapters.get(found.adapterId());
        return adapter != null && registered.stream().anyMatch(device -> adapter.carries(device, found));
    }


    /**
     * For each pairing-free adapter the adopted device lacks: the addable receiver at its
     * address, or else the single receiver with its name — and only when no other registered
     * device shares that name or that receiver's address, where it would belong instead.
     */
    private Device absorbAddable(Device device) {
        List<Device> others = registry.findAll().stream()
                .filter(other -> !other.id().equals(device.id()))
                .toList();
        Map<String, List<DiscoveredDevice>> byAdapter = addable().stream()
                .collect(Collectors.groupingBy(DiscoveredDevice::adapterId, LinkedHashMap::new, Collectors.toList()));
        Device result = device;
        for (Map.Entry<String, List<DiscoveredDevice>> entry : byAdapter.entrySet()) {
            if (result.hasAdapter(entry.getKey())) {
                continue;
            }
            Optional<DiscoveredDevice> match = DeviceMatching.absorbable(result, entry.getValue(), others);
            if (match.isPresent()) {
                result = result.withAdapter(entry.getKey(),
                        adapters.get(entry.getKey()).settingsFor(match.get()).orElseThrow());
            }
        }
        return result;
    }

    /** Kept for {@code DeviceManagerFallThroughTest}; see {@link DeviceMatching#uniqueId}. */
    static String uniqueId(List<Device> registered, String adapterId, String host) {
        return DeviceMatching.uniqueId(registered, adapterId, host);
    }

    /** (Re)connects every one of the device's adapters; see {@link DeviceConnections}. */
    private void connect(Device device) {
        connections.complete(connections.begin(device));
    }

    @Override
    @PreDestroy
    public void close() {
        connections.closeAll();
    }
}

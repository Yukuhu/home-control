package dev.andre.homecontrol.device;

import dev.andre.homecontrol.core.AdapterDiscovery;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceAdapter;
import dev.andre.homecontrol.core.DeviceDiscoveredEvent;
import dev.andre.homecontrol.core.DeviceEnrollment;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.core.DeviceRegistry;
import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.DeviceStateChangedEvent;
import dev.andre.homecontrol.core.DiscoveredDevice;
import dev.andre.homecontrol.storage.DataDirectory;
import dev.andre.homecontrol.storage.StorageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

import java.net.InetAddress;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The only place devices are added, merged, split or forgotten. Every operation runs in three phases:
 * <ol>
 *   <li>before the {@link RegistryLock}, whatever may wait on the network or on other code: the adapters' discovered
 *       lists and, for {@link #attach}, resolving hosts;</li>
 *   <li>under it, the decision and the registry writes, the adapters' local {@code forget}/{@code migrate}, and
 *       {@link DeviceConnections#begin}/{@link DeviceConnections#end}, so connects follow the registry's order;</li>
 *   <li>after it, closing handles, completing connects and publishing events.</li>
 * </ol>
 */
final class Enrollment implements DeviceEnrollment {

    private static final Logger log = LoggerFactory.getLogger(Enrollment.class);
    private static final String NO_DEVICE_PREFIX = "No device with id ";
    private static final String SWITCHED_OFF_PREFIX = "The ";
    private static final String SWITCHED_OFF_SUFFIX = " module is switched off";
    private static final String NOT_VISIBLE = "That device is no longer visible on the network";

    private final DeviceRegistry registry;
    private final Map<String, DeviceAdapter> adapters;
    /** The adapters that take part in discovery and merging, by id; an adapter that is not one sees nothing. */
    private final Map<String, AdapterDiscovery> discoveries = new LinkedHashMap<>();
    private final DeviceConnections connections;
    private final RegistryLock lock;
    private final ApplicationEventPublisher events;
    private final Function<String, Optional<InetAddress>> resolver;

    Enrollment(DeviceRegistry registry, Map<String, DeviceAdapter> adapters, DeviceConnections connections,
               RegistryLock lock, ApplicationEventPublisher events, Function<String, Optional<InetAddress>> resolver) {
        this.registry = registry;
        this.adapters = adapters;
        this.connections = connections;
        this.lock = lock;
        this.events = events;
        this.resolver = resolver;
        adapters.values().forEach(adapter -> {
            if (adapter instanceof AdapterDiscovery discovery) {
                discoveries.put(adapter.id(), discovery);
            }
        });
    }

    /** What one operation does to the connections and the screens once the registry lock is released. */
    private final class AfterLock {

        private final List<DeviceConnections.Connecting> connects = new ArrayList<>();
        private final List<String> removed = new ArrayList<>();

        void connect(Device device) {
            connects.add(connections.begin(device));
        }

        void remove(String id) {
            connections.end(id);
            removed.add(id);
        }

        void run() {
            removed.forEach(connections::closeRetired);
            connects.forEach(connections::complete);
            removed.forEach(id -> events.publishEvent(new DeviceStateChangedEvent(id, DeviceState.initial())));
        }
    }

    /**
     * Checks every entry of a running adapter before anything connects, so a bad one stops startup. Then brings each
     * device's settings up to date and connects it. An entry of a switched-off module is left as it is until its
     * module is on.
     */
    void start() {
        List<Device> registered = registry.findAll();
        registered.forEach(this::validate);
        AfterLock after = new AfterLock();
        synchronized (lock) {
            registered.forEach(device -> after.connect(migrate(device)));
        }
        after.run();
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

    /** Under the registry lock. */
    private Device migrate(Device device) {
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

    /**
     * Registers a freshly paired device and brings it up. Adapters the registry already has for
     * this id (a Cast entry on a re-paired Shield) are kept, and pairing-free receivers seen at
     * the same address or under the same name are merged in.
     */
    @Override
    public void adopt(Device device) {
        List<DiscoveredDevice> visible = discovered();
        AfterLock after = new AfterLock();
        synchronized (lock) {
            adoptLocked(device, visible, after);
        }
        after.run();
    }

    /** Under the registry lock; returns the device as saved. */
    private Device adoptLocked(Device device, List<DiscoveredDevice> visible, AfterLock after) {
        Device adopted = registry.findById(device.id())
                .map(existing -> DeviceMatching.keepOtherAdapters(existing, device))
                .orElse(device);
        adopted = absorbAddable(adopted, visible);
        registry.save(adopted);
        after.connect(adopted);
        return adopted;
    }

    @Override
    public void forget(String id) {
        AfterLock after = new AfterLock();
        synchronized (lock) {
            Optional<Device> registered = registry.findById(id);
            if (registered.isEmpty()) {
                return;
            }
            Device device = registered.get();
            device.adapters().keySet().forEach(adapterId -> {
                DeviceAdapter adapter = adapters.get(adapterId);
                if (adapter != null) {
                    adapter.forget(device);
                }
            });
            registry.delete(id);
            // Last, so a forget or delete that fails leaves the device registered and still connected.
            after.remove(id);
        }
        after.run();
    }

    /**
     * For prompt-paired adapters (webOS, Tizen): adds {@code adapterId} with {@code settings} to the
     * registered device at {@code host}, or registers a new device there, and (re)connects it.
     * Goes through {@link #adopt}, so pairing-free receivers at that address are absorbed as well.
     * Host names are resolved before the lock; one registered meanwhile is compared by name.
     */
    @Override
    public Device attach(String host, String name, DeviceKind kind, String adapterId, Map<String, String> settings) {
        List<Device> snapshot = registry.findAll();
        HostAddresses addresses = HostAddresses.resolve(
                Stream.concat(Stream.of(host), snapshot.stream().map(Device::host)).toList(), resolver);
        List<DiscoveredDevice> visible = discovered();
        AfterLock after = new AfterLock();
        Device adopted;
        synchronized (lock) {
            Device merged = DeviceMatching.attach(registry.findAll(), host, name, kind, adapterId, settings,
                    Instant.now(), addresses::same);
            adopted = adoptLocked(merged, visible, after);
        }
        after.run();
        return adopted;
    }

    List<DiscoveredDevice> discovered() {
        return discoveries.values().stream().flatMap(discovery -> discovery.discovered().stream()).toList();
    }

    /** Discovered devices that need the pairing flow. */
    @Override
    public List<DiscoveredDevice> pairable() {
        return discovered().stream().filter(found -> !pairingFree(found)).toList();
    }

    /** Pairing-free devices on the network that no registered device carries yet. */
    @Override
    public List<DiscoveredDevice> addable() {
        return addable(discovered(), registry.findAll());
    }

    private List<DiscoveredDevice> addable(List<DiscoveredDevice> visible, List<Device> registered) {
        return visible.stream()
                .filter(this::pairingFree)
                .filter(found -> !isRegistered(registered, found))
                .toList();
    }

    /** The setup page's "Add": merge into the matching device, or register a new one. */
    @Override
    public Device addDiscovered(String adapterId, String host, int port) {
        DeviceAdapter adapter = adapters.get(adapterId);
        if (adapter == null) {
            throw new IllegalArgumentException(SWITCHED_OFF_PREFIX + adapterId + SWITCHED_OFF_SUFFIX);
        }
        AdapterDiscovery discovery = discoveries.get(adapterId);
        if (discovery == null) {
            throw new IllegalArgumentException(NOT_VISIBLE);
        }
        DiscoveredDevice found = discovery.discovered().stream()
                .filter(candidate -> candidate.host().equalsIgnoreCase(host) && candidate.port() == port)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(NOT_VISIBLE));
        Map<String, String> settings = discovery.settingsFor(found)
                .orElseThrow(() -> new IllegalArgumentException(found.name() + " has to be paired, not added"));
        AfterLock after = new AfterLock();
        Device device;
        synchronized (lock) {
            List<Device> registered = registry.findAll();
            if (isRegistered(registered, found)) {
                throw new IllegalArgumentException(found.name() + " is already added");
            }
            device = DeviceMatching.owner(registered, found)
                    .map(target -> target.withAdapter(adapterId, settings))
                    .orElseGet(() -> new Device(DeviceMatching.uniqueId(registered, adapterId, found.host()), found.name(),
                            adapter.kind(), found.host(), Map.of(adapterId, settings), Instant.now()));
            registry.save(device);
            after.connect(device);
        }
        after.run();
        return device;
    }

    /**
     * Automatic merge (spec §5.1): only into an existing device, never creating one. A receiver
     * a registered device already carries by a stable identity (Cast's mDNS {@code id}) rather
     * than its address is re-pointed and reconnected when it answers at a new one — an adapter
     * matches {@link AdapterDiscovery#carries} on identity alone, so without this the device would
     * otherwise keep dialling the stale address forever and the receiver could never be re-added.
     */
    void onDiscovered(DeviceDiscoveredEvent event) {
        DiscoveredDevice found = event.device();
        AdapterDiscovery discovery = discoveries.get(found.adapterId());
        if (discovery == null) {
            return;
        }
        Optional<Map<String, String>> settings = discovery.settingsFor(found);
        if (settings.isEmpty()) {
            return;
        }
        AfterLock after = new AfterLock();
        synchronized (lock) {
            List<Device> registered = registry.findAll();
            Optional<Device> carrier = registered.stream().filter(device -> discovery.carries(device, found)).findFirst();
            if (carrier.isPresent()) {
                reconnectIfMoved(carrier.get(), found.adapterId(), settings.get(), after);
            } else {
                DeviceMatching.owner(registered, found).ifPresent(target -> {
                    Device merged = target.withAdapter(found.adapterId(), settings.get());
                    registry.save(merged);
                    after.connect(merged);
                    log.info("Merged {} receiver {} into {}", found.adapterId(), found.name(), target.name());
                });
            }
        }
        after.run();
    }

    /**
     * Re-points a device's adapter entry to a receiver's new address once mDNS says it moved,
     * and reconnects it there. A no-op when the host and port the adapter already stored still
     * match, so a routine re-announcement at the same address never tears the connection down.
     * Under the registry lock.
     */
    private void reconnectIfMoved(Device device, String adapterId, Map<String, String> newSettings, AfterLock after) {
        Map<String, String> current = device.adapterSettings(adapterId);
        if (Objects.equals(current.get("host"), newSettings.get("host"))
                && Objects.equals(current.get("port"), newSettings.get("port"))) {
            return;
        }
        Device moved = device.withAdapter(adapterId, newSettings);
        registry.save(moved);
        after.connect(moved);
        log.info("{} receiver for {} answered at a new address; reconnecting", adapterId, device.name());
    }

    /** Moves every adapter of {@code source} into {@code target} and removes {@code source}. */
    @Override
    public Device merge(String targetId, String sourceId) {
        if (targetId.equals(sourceId)) {
            throw new IllegalArgumentException("Pick two different devices to merge");
        }
        AfterLock after = new AfterLock();
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
                    throw new IllegalArgumentException(SWITCHED_OFF_PREFIX + adapterId + SWITCHED_OFF_SUFFIX);
                }
                if (boundToDeviceId(adapterId)) {
                    throw new IllegalArgumentException("Merge the other way round: the " + adapterId
                            + " pairing of " + source.name() + " only works under its own id");
                }
                merged = merged.withAdapter(adapterId, entry.getValue());
            }
            // Credentials move with the settings, so the source is removed WITHOUT adapter.forget().
            registry.delete(sourceId);
            registry.save(merged);
            // After the writes, so a merge that cannot be saved leaves the source connected.
            after.remove(sourceId);
            after.connect(merged);
        }
        after.run();
        return merged;
    }

    /** Moves one adapter out of a device into a new device of its own. */
    @Override
    public Device split(String id, String adapterId) {
        AfterLock after = new AfterLock();
        Device split;
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
                throw new IllegalArgumentException(SWITCHED_OFF_PREFIX + adapterId + SWITCHED_OFF_SUFFIX);
            }
            if (boundToDeviceId(adapterId)) {
                throw new IllegalArgumentException("The " + adapterId + " pairing belongs to " + device.name()
                        + " and cannot be split off; split the other connections instead");
            }
            Map<String, Map<String, String>> remaining = new LinkedHashMap<>(device.adapters());
            remaining.remove(adapterId);
            Device rest = new Device(device.id(), device.name(), device.kind(), device.host(), remaining, device.lastSeen());
            // A receiver merged in from another address takes that address with it.
            AdapterDiscovery discovery = discoveries.get(adapterId);
            String host = discovery == null ? device.host() : discovery.hostOf(device);
            split = new Device(DeviceMatching.uniqueId(registry.findAll(), adapterId, host),
                    device.name() + " (" + adapterId + ")",
                    adapter.kind(), host,
                    Map.of(adapterId, device.adapterSettings(adapterId)), device.lastSeen());
            registry.save(rest);
            registry.save(split);
            after.connect(rest);
            after.connect(split);
        }
        after.run();
        return split;
    }

    private boolean pairingFree(DiscoveredDevice found) {
        AdapterDiscovery discovery = discoveries.get(found.adapterId());
        return discovery != null && discovery.settingsFor(found).isPresent();
    }

    /** True when the adapter keeps its pairing under the device id, so its entry never moves to another id. */
    private boolean boundToDeviceId(String adapterId) {
        AdapterDiscovery discovery = discoveries.get(adapterId);
        return discovery != null && discovery.credentialsBoundToDeviceId();
    }

    /**
     * Registered once any device carries it through its adapter — judged by the adapter, at
     * the address (or identity) that adapter entry remembers, not the device's own address.
     * That includes a device split off earlier, which is why a split is never merged back
     * automatically.
     */
    private boolean isRegistered(List<Device> registered, DiscoveredDevice found) {
        AdapterDiscovery discovery = discoveries.get(found.adapterId());
        return discovery != null && registered.stream().anyMatch(device -> discovery.carries(device, found));
    }

    /**
     * For each pairing-free adapter the adopted device lacks: the addable receiver at its
     * address, or else the single receiver with its name — and only when no other registered
     * device shares that name or that receiver's address, where it would belong instead.
     * Under the registry lock, from the receivers seen before it.
     */
    private Device absorbAddable(Device device, List<DiscoveredDevice> visible) {
        List<Device> registered = registry.findAll();
        List<Device> others = registered.stream()
                .filter(other -> !other.id().equals(device.id()))
                .toList();
        Map<String, List<DiscoveredDevice>> byAdapter = addable(visible, registered).stream()
                .collect(Collectors.groupingBy(DiscoveredDevice::adapterId, LinkedHashMap::new, Collectors.toList()));
        Device result = device;
        for (Map.Entry<String, List<DiscoveredDevice>> entry : byAdapter.entrySet()) {
            if (result.hasAdapter(entry.getKey())) {
                continue;
            }
            Optional<DiscoveredDevice> match = DeviceMatching.absorbable(result, entry.getValue(), others);
            if (match.isPresent()) {
                result = result.withAdapter(entry.getKey(),
                        discoveries.get(entry.getKey()).settingsFor(match.get()).orElseThrow());
            }
        }
        return result;
    }
}

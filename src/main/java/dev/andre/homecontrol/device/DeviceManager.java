package dev.andre.homecontrol.device;

import dev.andre.homecontrol.core.Action;
import dev.andre.homecontrol.core.Capability;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceAdapter;
import dev.andre.homecontrol.core.DeviceDiscoveredEvent;
import dev.andre.homecontrol.core.DeviceHandle;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.core.DeviceNotFoundException;
import dev.andre.homecontrol.core.DeviceOfflineException;
import dev.andre.homecontrol.core.DeviceRegistry;
import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.DeviceStateChangedEvent;
import dev.andre.homecontrol.core.DeviceStates;
import dev.andre.homecontrol.core.DiscoveredDevice;
import dev.andre.homecontrol.core.Hosts;
import dev.andre.homecontrol.core.UnsupportedActionException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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

    private static final Logger log = LoggerFactory.getLogger(DeviceManager.class);

    private final DeviceRegistry registry;
    private final Map<String, DeviceAdapter> adapters = new LinkedHashMap<>();
    private final ApplicationEventPublisher events;
    /** device id → (adapter id → handle), in the device's adapter order. */
    private final Map<String, Map<String, DeviceHandle>> handles = new ConcurrentHashMap<>();
    /**
     * device id → (adapter id → last state that adapter reported), in the device's adapter
     * order. Each connect installs a fresh map, so a handle of a closed generation that
     * reports late finds its map replaced and is ignored. Written under {@link #lock} (the map
     * itself) and under the inner map's own monitor (its entries).
     */
    private final Map<String, Map<String, DeviceState>> reported = new ConcurrentHashMap<>();
    /**
     * Guards every write to {@link #handles} and {@link #reported} (via {@link #tryConnect},
     * {@link #closeHandles}, {@link #adopt}, {@link #forget}, {@link #addDiscovered},
     * {@link #onDiscovered}, {@link #merge}, {@link #split} and {@link #close}) so that an adopt
     * racing another adopt, an adopt racing a forget, or a discovery merge on the mDNS thread
     * racing any of them, can never leave two live handles for one device or a live handle
     * for a device that {@link #forget} just deleted from the registry. The registry
     * read-modify-writes of those methods run under it for the same reason.
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

    /** The composed state of the device's adapters; an unknown or handle-less device reads as DISCONNECTED. */
    public DeviceState state(String id) {
        Map<String, DeviceState> states = reported.get(id);
        if (states == null) {
            return DeviceState.initial();
        }
        synchronized (states) {
            return DeviceStates.compose(List.copyOf(states.values()));
        }
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
     * Tries the device's adapters that declare the needed capability, in order. An adapter that
     * could not even send — unsupported, offline, or declaring the capability without a live
     * handle (not yet connected, or a failed connect) — hands over to the next; an adapter whose
     * device answered "no" ({@link dev.andre.homecontrol.core.ActionFailedException}) ends it.
     * If nobody could send, the first offline reason wins over the last unsupported one; only a
     * capability none of the device's adapters declare is plainly unsupported. Nothing is
     * retried later (commands are ephemeral).
     */
    public void execute(String id, Action action) {
        Device device = registry.findById(id)
                .orElseThrow(() -> new DeviceNotFoundException("No device with id " + id));
        Map<String, DeviceHandle> deviceHandles = handles.getOrDefault(id, Map.of());
        DeviceOfflineException firstOffline = null;
        UnsupportedActionException lastUnsupported = null;
        for (String adapterId : device.adapters().keySet()) {
            DeviceAdapter adapter = adapters.get(adapterId);
            if (adapter == null || !adapter.capabilities(device).contains(action.requires())) {
                continue;
            }
            DeviceHandle handle = deviceHandles.get(adapterId);
            if (handle == null) {
                if (firstOffline == null) {
                    firstOffline = new DeviceOfflineException(device.name() + " is not connected");
                }
                continue;
            }
            try {
                handle.execute(action);
                return;
            } catch (DeviceOfflineException e) {
                if (firstOffline == null) {
                    firstOffline = e;
                }
            } catch (UnsupportedActionException e) {
                lastUnsupported = e;
            }
        }
        if (firstOffline != null) {
            throw firstOffline;
        }
        if (lastUnsupported != null) {
            throw lastUnsupported;
        }
        throw new UnsupportedActionException(device.name() + " cannot perform " + action);
    }

    /**
     * Registers a freshly paired device and brings it up. Adapters the registry already has for
     * this id (a Cast entry on a re-paired Shield) are kept, and pairing-free receivers seen at
     * the same address or under the same name are merged in.
     */
    public void adopt(Device device) {
        synchronized (lock) {
            Device adopted = registry.findById(device.id())
                    .map(existing -> keepOtherAdapters(existing, device))
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

    /**
     * For prompt-paired adapters (webOS, Tizen): adds {@code adapterId} with {@code settings} to the
     * registered device at {@code host}, or registers a new device there, and (re)connects it.
     * Goes through {@link #adopt}, so pairing-free receivers at that address are absorbed as well.
     */
    public Device attach(String host, String name, DeviceKind kind, String adapterId, Map<String, String> settings) {
        Device merged;
        synchronized (lock) {
            merged = DeviceMerge.attach(registry.findAll(), host, name, kind, adapterId, settings,
                    Instant.now(), Hosts::same);
            adopt(merged);
            merged = registry.findById(merged.id()).orElse(merged);
        }
        return merged;
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
            Device device = bestMatch(registered, found)
                    .map(target -> target.withAdapter(adapterId, settings))
                    .orElseGet(() -> new Device(uniqueId(registered, adapterId, found.host()), found.name(),
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
            bestMatch(registered, found).ifPresent(target -> {
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
                    .orElseThrow(() -> new IllegalArgumentException("No device with id " + targetId));
            Device source = registry.findById(sourceId)
                    .orElseThrow(() -> new IllegalArgumentException("No device with id " + sourceId));
            merged = target;
            for (Map.Entry<String, Map<String, String>> entry : source.adapters().entrySet()) {
                String adapterId = entry.getKey();
                if (target.hasAdapter(adapterId)) {
                    throw new IllegalArgumentException(target.name() + " already has a " + adapterId + " connection");
                }
                DeviceAdapter adapter = adapters.get(adapterId);
                if (adapter != null && adapter.credentialsBoundToDeviceId()) {
                    throw new IllegalArgumentException("Merge the other way round: the " + adapterId
                            + " pairing of " + source.name() + " only works under its own id");
                }
                merged = merged.withAdapter(adapterId, entry.getValue());
            }
            // Credentials move with the settings, so the source is removed WITHOUT adapter.forget().
            closeHandles(sourceId);
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
                    .orElseThrow(() -> new IllegalArgumentException("No device with id " + id));
            if (!device.hasAdapter(adapterId)) {
                throw new IllegalArgumentException(device.name() + " has no " + adapterId + " connection");
            }
            if (device.adapters().size() < 2) {
                throw new IllegalArgumentException(device.name() + " has only one connection; there is nothing to split");
            }
            DeviceAdapter adapter = adapters.get(adapterId);
            if (adapter != null && adapter.credentialsBoundToDeviceId()) {
                throw new IllegalArgumentException("The " + adapterId + " pairing belongs to " + device.name()
                        + " and cannot be split off; split the other connections instead");
            }
            Map<String, Map<String, String>> remaining = new LinkedHashMap<>(device.adapters());
            remaining.remove(adapterId);
            Device rest = new Device(device.id(), device.name(), device.kind(), device.host(), remaining, device.lastSeen());
            // A receiver merged in from another address takes that address with it.
            String host = adapter != null ? adapter.hostOf(device) : device.host();
            Device split = new Device(uniqueId(registry.findAll(), adapterId, host),
                    device.name() + " (" + adapterId + ")",
                    adapter != null ? adapter.kind() : device.kind(), host,
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

    /** Same address first; otherwise the single device with the same name. Never one that already has the adapter. */
    static Optional<Device> bestMatch(List<Device> registered, DiscoveredDevice found) {
        List<Device> candidates = registered.stream().filter(device -> !device.hasAdapter(found.adapterId())).toList();
        Optional<Device> byHost = candidates.stream()
                .filter(device -> device.host().equalsIgnoreCase(found.host()))
                .findFirst();
        if (byHost.isPresent()) {
            return byHost;
        }
        List<Device> byName = candidates.stream().filter(device -> sameName(device.name(), found.name())).toList();
        return byName.size() == 1 ? Optional.of(byName.getFirst()) : Optional.empty();
    }

    static boolean sameName(String a, String b) {
        return a != null && b != null && a.strip().equalsIgnoreCase(b.strip());
    }

    /** The re-paired adapters replace their old entries; every other adapter stays, in its place. */
    static Device keepOtherAdapters(Device existing, Device adopted) {
        Map<String, Map<String, String>> merged = new LinkedHashMap<>(existing.adapters());
        merged.putAll(adopted.adapters());
        return new Device(adopted.id(), adopted.name(), existing.kind(), adopted.host(), merged, adopted.lastSeen());
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
            Optional<DiscoveredDevice> match = absorbable(result, entry.getValue(), others);
            if (match.isPresent()) {
                result = result.withAdapter(entry.getKey(),
                        adapters.get(entry.getKey()).settingsFor(match.get()).orElseThrow());
            }
        }
        return result;
    }

    private static Optional<DiscoveredDevice> absorbable(Device device, List<DiscoveredDevice> receivers,
                                                         List<Device> others) {
        Optional<DiscoveredDevice> byHost = receivers.stream()
                .filter(found -> found.host().equalsIgnoreCase(device.host()))
                .findFirst();
        if (byHost.isPresent()) {
            return byHost;
        }
        List<DiscoveredDevice> byName = receivers.stream()
                .filter(found -> sameName(found.name(), device.name()))
                .toList();
        if (byName.size() != 1) {
            return Optional.empty();
        }
        DiscoveredDevice only = byName.getFirst();
        boolean belongsElsewhere = others.stream().anyMatch(other ->
                sameName(other.name(), device.name()) || other.host().equalsIgnoreCase(only.host()));
        return belongsElsewhere ? Optional.empty() : Optional.of(only);
    }

    static String uniqueId(List<Device> registered, String adapterId, String host) {
        String base = adapterId + "-" + host.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        Set<String> taken = registered.stream().map(Device::id).collect(Collectors.toSet());
        String id = base;
        for (int n = 2; taken.contains(id); n++) {
            id = base + "-" + n;
        }
        return id;
    }

    /**
     * Connects every one of the device's adapters, under {@link #lock} so this can never
     * interleave with another {@link #tryConnect}/{@link #closeHandles} for the same or a
     * different device. A failing adapter never leaves the device half-connected: its
     * {@link RuntimeException} is caught and logged, whatever handles this call already
     * opened for the device are closed, and the device is left with no handles at all —
     * {@link #state} then reads it as DISCONNECTED — rather than failing {@link #start} and
     * leaking those handles, or every other device's connect along with it. A DISCONNECTED
     * state is then published for the device: its previous handle was closed and silenced
     * first, so whatever that handle last published would otherwise stay on every screen.
     */
    private void connect(Device device) {
        if (!tryConnect(device)) {
            events.publishEvent(new DeviceStateChangedEvent(device.id(), DeviceState.initial()));
        }
    }

    /** {@link #connect}'s locked part; false when an adapter failed and the device has no handles. */
    private boolean tryConnect(Device device) {
        synchronized (lock) {
            closeHandles(device.id());
            Map<String, DeviceState> states = new LinkedHashMap<>();
            device.adapters().keySet().stream()
                    .filter(adapters::containsKey)
                    .forEach(adapterId -> states.put(adapterId, DeviceState.initial()));
            reported.put(device.id(), states);
            Map<String, DeviceHandle> deviceHandles = new LinkedHashMap<>();
            for (String adapterId : List.copyOf(states.keySet())) {
                try {
                    deviceHandles.put(adapterId, adapters.get(adapterId).connect(device,
                            state -> report(device.id(), states, adapterId, state)));
                } catch (RuntimeException e) {
                    log.warn("Could not connect {} via the {} adapter; leaving it disconnected",
                            device.id(), adapterId, e);
                    reported.remove(device.id());
                    deviceHandles.values().forEach(DeviceHandle::close);
                    return false;
                }
            }
            handles.put(device.id(), deviceHandles);
            return true;
        }
    }

    /**
     * Publishes the composed state inside the generation's monitor, so two adapters' updates
     * reach SSE in the order they were composed.
     */
    private void report(String deviceId, Map<String, DeviceState> states, String adapterId, DeviceState state) {
        synchronized (states) {
            if (reported.get(deviceId) != states) {
                return; // a handle from a closed generation reporting late
            }
            states.put(adapterId, state);
            events.publishEvent(new DeviceStateChangedEvent(deviceId,
                    DeviceStates.compose(List.copyOf(states.values()))));
        }
    }

    private void closeHandles(String id) {
        synchronized (lock) {
            reported.remove(id);
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

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
import dev.andre.homecontrol.core.SpeakerTopology;
import dev.andre.homecontrol.core.TvInput;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Owns one live {@link DeviceHandle} per registered device and adapter, composes their states
 * into one per device, and is the only place devices are added, merged, split or forgotten.
 * It is the only thing the web layer talks to about devices. Every device gets a handle now that
 * {@link DeviceStateChangedEvent} carries the device id — the v1 "active device only" rule
 * existed solely because it did not.
 */
@Service
public class DeviceManager implements AutoCloseable {



    private final Map<String, DeviceAdapter> adapters = new LinkedHashMap<>();
    private final AdapterSettingsStore settings;
    private final DeviceConnections connections;
    private final RegisteredDevices queries;
    private final CommandRouter commands;
    private final Enrollment enrollment;

    public DeviceManager(DeviceRegistry registry, List<DeviceAdapter> adapters,
                         ApplicationEventPublisher events) {
        adapters.forEach(adapter -> this.adapters.put(adapter.id(), adapter));
        RegistryLock lock = new RegistryLock();
        this.settings = new AdapterSettingsStore(registry, this.adapters, lock);
        this.connections = new DeviceConnections(this.adapters, events, settings::updateAdapterSettings);
        this.queries = new RegisteredDevices(registry, this.adapters, connections);
        this.commands = new CommandRouter(registry, this.adapters, connections);
        this.enrollment = new Enrollment(registry, this.adapters, connections, lock, events, HostAddresses::lookup);
    }

    /** Validates, migrates and connects every registered device; see {@link Enrollment#start}. */
    @PostConstruct
    public void start() {
        enrollment.start();
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

    /** Registers a freshly paired device and brings it up; see {@link Enrollment#adopt}. */
    public void adopt(Device device) {
        enrollment.adopt(device);
    }

    public void forget(String id) {
        enrollment.forget(id);
    }

    /** Adds a prompt-paired adapter to the device at {@code host}, or registers one; see {@link Enrollment#attach}. */
    public Device attach(String host, String name, DeviceKind kind, String adapterId, Map<String, String> settings) {
        return enrollment.attach(host, name, kind, adapterId, settings);
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
        return enrollment.discovered();
    }

    /** Discovered devices that need the pairing flow. */
    public List<DiscoveredDevice> pairable() {
        return enrollment.pairable();
    }

    /** Pairing-free devices on the network that no registered device carries yet. */
    public List<DiscoveredDevice> addable() {
        return enrollment.addable();
    }

    /** The setup page's "Add": merge into the matching device, or register a new one. */
    public Device addDiscovered(String adapterId, String host, int port) {
        return enrollment.addDiscovered(adapterId, host, port);
    }

    /** The automatic merge; see {@link Enrollment#onDiscovered}. */
    @EventListener
    public void onDiscovered(DeviceDiscoveredEvent event) {
        enrollment.onDiscovered(event);
    }

    /** Merges receivers seen during startup, for {@link DiscoveryCatchUp}. */
    public void mergeVisibleReceivers() {
        enrollment.mergeVisibleReceivers();
    }

    /** Moves every adapter of {@code source} into {@code target} and removes {@code source}. */
    public Device merge(String targetId, String sourceId) {
        return enrollment.merge(targetId, sourceId);
    }

    /** Moves one adapter out of a device into a new device of its own. */
    public Device split(String id, String adapterId) {
        return enrollment.split(id, adapterId);
    }


    /** Kept for {@code DeviceManagerFallThroughTest}; see {@link DeviceMatching#uniqueId}. */
    static String uniqueId(List<Device> registered, String adapterId, String host) {
        return DeviceMatching.uniqueId(registered, adapterId, host);
    }

    @Override
    @PreDestroy
    public void close() {
        connections.closeAll();
    }
}

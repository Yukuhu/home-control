package dev.andre.homecontrol.device;

import dev.andre.homecontrol.core.Capability;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceAdapter;
import dev.andre.homecontrol.core.DeviceQueries;
import dev.andre.homecontrol.core.DeviceRegistry;
import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.ForegroundAppReporting;
import dev.andre.homecontrol.core.GroupListing;
import dev.andre.homecontrol.core.InputListing;
import dev.andre.homecontrol.core.SpeakerTopology;
import dev.andre.homecontrol.core.TvInput;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Read-only answers about the registered devices and their live state. Lock-free. */
final class RegisteredDevices implements DeviceQueries {

    private final DeviceRegistry registry;
    private final Map<String, DeviceAdapter> adapters;
    private final DeviceConnections connections;

    RegisteredDevices(DeviceRegistry registry, Map<String, DeviceAdapter> adapters, DeviceConnections connections) {
        this.registry = registry;
        this.adapters = adapters;
        this.connections = connections;
    }

    @Override
    public List<Device> devices() {
        return registry.findAll().stream()
                .sorted(Comparator.comparing(Device::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    @Override
    public Optional<Device> device(String id) {
        return registry.findById(id);
    }

    /** The most recently paired device: what {@code /} shows when no device is selected. */
    @Override
    public Optional<Device> defaultDevice() {
        return registry.first();
    }

    /** The composed state of the device's adapters; an unknown or handle-less device reads as DISCONNECTED. */
    @Override
    public DeviceState state(String id) {
        return connections.state(id);
    }

    @Override
    public Map<String, DeviceState> states() {
        Map<String, DeviceState> states = new LinkedHashMap<>();
        devices().forEach(device -> states.put(device.id(), state(device.id())));
        return states;
    }

    @Override
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

    /** The best foreground-app reporting among the device's adapters; {@code NONE} for an unknown id. */
    @Override
    public ForegroundAppReporting foregroundAppReporting(String id) {
        return registry.findById(id)
                .flatMap(device -> device.adapters().keySet().stream()
                        .map(adapters::get)
                        .filter(Objects::nonNull)
                        .map(adapter -> adapter.foregroundAppReporting(device))
                        .min(Comparator.naturalOrder()))
                .orElse(ForegroundAppReporting.NONE);
    }

    /** Whether the adapter's module is switched on: a device's entry for it in devices.json does not say so. */
    @Override
    public boolean adapterEnabled(String adapterId) {
        return adapters.containsKey(adapterId);
    }

    /** Grouping as seen by the first of the device's handles that knows it; empty otherwise. */
    @Override
    public Optional<SpeakerTopology> speakerTopology(String id) {
        return connections.handles(id).values().stream()
                .map(handle -> handle.feature(GroupListing.class))
                .flatMap(Optional::stream)
                .map(GroupListing::speakerTopology)
                .flatMap(Optional::stream)
                .findFirst();
    }

    /** Inputs from the first of the device's handles that lists any; empty when none does. */
    @Override
    public List<TvInput> inputs(String id) {
        return connections.handles(id).values().stream()
                .map(handle -> handle.feature(InputListing.class))
                .flatMap(Optional::stream)
                .map(InputListing::inputs)
                .filter(list -> !list.isEmpty())
                .findFirst()
                .orElse(List.of());
    }
}

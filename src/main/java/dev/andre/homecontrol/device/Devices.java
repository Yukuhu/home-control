package dev.andre.homecontrol.device;

import dev.andre.homecontrol.core.DeviceAdapter;
import dev.andre.homecontrol.core.DeviceRegistry;
import org.springframework.context.ApplicationEventPublisher;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The device collaborators, sharing one registry lock and one connection map. {@link #assemble} is the one place they
 * are wired together.
 */
public record Devices(RegisteredDevices queries, CommandRouter commands, Enrollment enrollment,
                      AdapterSettingsStore settings, DeviceConnections connections) {

    /** Wires the collaborators over the switched-on adapters, by id; what the handles learn goes to {@code settings}. */
    public static Devices assemble(DeviceRegistry registry, List<DeviceAdapter> adapters,
                                   ApplicationEventPublisher events) {
        Map<String, DeviceAdapter> byId = new LinkedHashMap<>();
        adapters.forEach(adapter -> byId.put(adapter.id(), adapter));
        RegistryLock lock = new RegistryLock();
        AdapterSettingsStore settings = new AdapterSettingsStore(registry, byId, lock);
        DeviceConnections connections = new DeviceConnections(byId, events, settings::updateAdapterSettings);
        return new Devices(new RegisteredDevices(registry, byId, connections),
                new CommandRouter(registry, byId, connections),
                new Enrollment(registry, byId, connections, lock, events, HostAddresses::lookup),
                settings, connections);
    }
}

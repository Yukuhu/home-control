package dev.andre.homecontrol.device;

import dev.andre.homecontrol.core.DeviceAdapter;
import dev.andre.homecontrol.core.DeviceCommands;
import dev.andre.homecontrol.core.DeviceDiscoveredEvent;
import dev.andre.homecontrol.core.DeviceEnrollment;
import dev.andre.homecontrol.core.DeviceQueries;
import dev.andre.homecontrol.core.DeviceRegistry;
import dev.andre.homecontrol.core.DeviceSettings;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The device collaborators, sharing one registry lock and one connection map, behind the four {@code core}
 * interfaces. {@link #assemble} is the one place they are wired together; the application's configuration exposes
 * each interface as a bean, starts this once the adapters exist, and closes it on shutdown.
 */
public final class Devices implements AutoCloseable {

    private final RegisteredDevices queries;
    private final CommandRouter commands;
    private final Enrollment enrollment;
    private final AdapterSettingsStore settings;
    private final DeviceConnections connections;

    private Devices(RegisteredDevices queries, CommandRouter commands, Enrollment enrollment,
                    AdapterSettingsStore settings, DeviceConnections connections) {
        this.queries = queries;
        this.commands = commands;
        this.enrollment = enrollment;
        this.settings = settings;
        this.connections = connections;
    }

    /** Wires the collaborators over the switched-on adapters, by id; what the handles learn goes to the settings store. */
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

    public DeviceQueries queries() {
        return queries;
    }

    public DeviceCommands commands() {
        return commands;
    }

    public DeviceEnrollment enrollment() {
        return enrollment;
    }

    public DeviceSettings settings() {
        return settings;
    }

    /** Validates, migrates and connects every registered device; see {@link Enrollment#start}. */
    public void start() {
        enrollment.start();
    }

    /** The automatic merge of what discovery announces; see {@link Enrollment#onDiscovered}. */
    @EventListener
    public void onDiscovered(DeviceDiscoveredEvent event) {
        enrollment.onDiscovered(event);
    }

    /** Closes every connection, including one still being completed. */
    @Override
    public void close() {
        connections.closeAll();
    }
}

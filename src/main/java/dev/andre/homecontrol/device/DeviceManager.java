package dev.andre.homecontrol.device;

import dev.andre.homecontrol.core.Action;
import dev.andre.homecontrol.core.CastAppQuery;
import dev.andre.homecontrol.core.Capability;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceAdapter;
import dev.andre.homecontrol.core.DeviceCommands;
import dev.andre.homecontrol.core.DeviceDiscoveredEvent;
import dev.andre.homecontrol.core.DeviceEnrollment;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.core.DeviceQueries;
import dev.andre.homecontrol.core.DeviceRegistry;
import dev.andre.homecontrol.core.DeviceSettings;
import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.DiscoveredDevice;
import dev.andre.homecontrol.core.ForegroundAppReporting;
import dev.andre.homecontrol.core.SpeakerTopology;
import dev.andre.homecontrol.core.TvInput;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The one bean the rest of the application talks to about devices, delegating each call to the collaborator that
 * {@link Devices#assemble} wired for it. Its callers move onto the four {@code core} interfaces it implements, and
 * then it goes.
 */
@Service
public class DeviceManager implements DeviceQueries, DeviceCommands, DeviceEnrollment, DeviceSettings, AutoCloseable {

    private final Devices parts;

    public DeviceManager(DeviceRegistry registry, List<DeviceAdapter> adapters, ApplicationEventPublisher events) {
        this.parts = Devices.assemble(registry, adapters, events);
    }

    /** Validates, migrates and connects every registered device; see {@link Enrollment#start}. */
    @PostConstruct
    public void start() {
        parts.start();
    }

    @Override
    public List<Device> devices() {
        return parts.queries().devices();
    }

    @Override
    public Optional<Device> device(String id) {
        return parts.queries().device(id);
    }

    @Override
    public Optional<Device> defaultDevice() {
        return parts.queries().defaultDevice();
    }

    @Override
    public DeviceState state(String id) {
        return parts.queries().state(id);
    }

    @Override
    public Map<String, DeviceState> states() {
        return parts.queries().states();
    }

    @Override
    public Set<Capability> capabilities(String id) {
        return parts.queries().capabilities(id);
    }

    @Override
    public void execute(String id, Action action) {
        parts.commands().execute(id, action);
    }

    @Override
    public Map<String, Object> query(String id, CastAppQuery query) {
        return parts.commands().query(id, query);
    }

    @Override
    public boolean adapterEnabled(String adapterId) {
        return parts.queries().adapterEnabled(adapterId);
    }

    @Override
    public void adopt(Device device) {
        parts.enrollment().adopt(device);
    }

    @Override
    public void forget(String id) {
        parts.enrollment().forget(id);
    }

    @Override
    public Device attach(String host, String name, DeviceKind kind, String adapterId, Map<String, String> settings) {
        return parts.enrollment().attach(host, name, kind, adapterId, settings);
    }

    @Override
    public ForegroundAppReporting foregroundAppReporting(String id) {
        return parts.queries().foregroundAppReporting(id);
    }

    @Override
    public boolean wakesOnLan(String id) {
        return parts.settings().wakesOnLan(id);
    }

    @Override
    public Optional<String> wakeOnLanMac(String id) {
        return parts.settings().wakeOnLanMac(id);
    }

    @Override
    public void setWakeOnLanMac(String id, String mac) {
        parts.settings().setWakeOnLanMac(id, mac);
    }

    @Override
    public Optional<SpeakerTopology> speakerTopology(String id) {
        return parts.queries().speakerTopology(id);
    }

    @Override
    public List<TvInput> inputs(String id) {
        return parts.queries().inputs(id);
    }

    @Override
    public List<DiscoveredDevice> pairable() {
        return parts.enrollment().pairable();
    }

    @Override
    public List<DiscoveredDevice> addable() {
        return parts.enrollment().addable();
    }

    @Override
    public Device addDiscovered(String adapterId, String host, int port) {
        return parts.enrollment().addDiscovered(adapterId, host, port);
    }

    /** The automatic merge; see {@link Enrollment#onDiscovered}. */
    @EventListener
    public void onDiscovered(DeviceDiscoveredEvent event) {
        parts.onDiscovered(event);
    }

    @Override
    public Device merge(String targetId, String sourceId) {
        return parts.enrollment().merge(targetId, sourceId);
    }

    @Override
    public Device split(String id, String adapterId) {
        return parts.enrollment().split(id, adapterId);
    }

    @Override
    @PreDestroy
    public void close() {
        parts.close();
    }
}

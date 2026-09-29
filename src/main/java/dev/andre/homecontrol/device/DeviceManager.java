package dev.andre.homecontrol.device;

import dev.andre.homecontrol.core.Action;
import dev.andre.homecontrol.core.CastAppQuery;
import dev.andre.homecontrol.core.Capability;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceCommands;
import dev.andre.homecontrol.core.DeviceEnrollment;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.core.DeviceQueries;
import dev.andre.homecontrol.core.DeviceSettings;
import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.DiscoveredDevice;
import dev.andre.homecontrol.core.ForegroundAppReporting;
import dev.andre.homecontrol.core.SpeakerTopology;
import dev.andre.homecontrol.core.TvInput;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * What the callers not yet moved onto the four {@code core} interfaces still inject: it forwards each call to the
 * {@link Devices} the application's configuration assembled, starts and closes. It goes once they have moved.
 */
public class DeviceManager implements DeviceQueries, DeviceCommands, DeviceEnrollment, DeviceSettings {

    private final Devices parts;

    public DeviceManager(Devices parts) {
        this.parts = parts;
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


    @Override
    public Device merge(String targetId, String sourceId) {
        return parts.enrollment().merge(targetId, sourceId);
    }

    @Override
    public Device split(String id, String adapterId) {
        return parts.enrollment().split(id, adapterId);
    }
}

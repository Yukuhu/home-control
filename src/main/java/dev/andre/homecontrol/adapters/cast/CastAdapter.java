package dev.andre.homecontrol.adapters.cast;

import dev.andre.homecontrol.core.Action;
import dev.andre.homecontrol.core.Capability;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceAdapter;
import dev.andre.homecontrol.core.DeviceHandle;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.core.DeviceOfflineException;
import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.DiscoveredDevice;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/** Google Cast receivers: Chromecast, Cast TVs and speakers, the Shield's built-in Cast. */
public class CastAdapter implements DeviceAdapter {

    public static final String ID = CastSettings.ADAPTER_ID;

    private final CastDiscovery discovery;

    public CastAdapter(CastDiscovery discovery) {
        this.discovery = discovery;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public DeviceKind kind() {
        return DeviceKind.CAST;
    }

    @Override
    public Set<Capability> capabilities(Device device) {
        return EnumSet.noneOf(Capability.class);
    }

    @Override
    public DeviceHandle connect(Device device, Consumer<DeviceState> onChange) {
        DeviceState offline = DeviceState.initial();
        onChange.accept(offline);
        return new OfflineHandle(offline);
    }

    @Override
    public List<DiscoveredDevice> discovered() {
        return discovery.devices();
    }

    @Override
    public String hostOf(Device device) {
        return CastSettings.hostOf(device);
    }

    /** The mDNS {@code id} survives an address change; otherwise the receiver's own address decides. */
    @Override
    public boolean carries(Device device, DiscoveredDevice found) {
        if (!device.hasAdapter(ID)) {
            return false;
        }
        String castId = device.adapterSettings(ID).get(CastSettings.CAST_ID);
        return (castId != null && castId.equals(found.attributes().get("id")))
                || hostOf(device).equalsIgnoreCase(found.host());
    }

    @Override
    public Optional<Map<String, String>> settingsFor(DiscoveredDevice found) {
        return ID.equals(found.adapterId()) ? Optional.of(CastSettings.from(found).toMap()) : Optional.empty();
    }

    private record OfflineHandle(DeviceState state) implements DeviceHandle {

        @Override
        public void execute(Action action) {
            throw new DeviceOfflineException("Cast control is not available yet");
        }

        @Override
        public void close() {
        }
    }
}

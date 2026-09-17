package dev.andre.homecontrol.adapters.sonos;

import dev.andre.homecontrol.adapters.upnp.protocol.SoapClient;
import dev.andre.homecontrol.core.Capability;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceAdapter;
import dev.andre.homecontrol.core.DeviceHandle;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.DiscoveredDevice;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Sonos rooms (spec §4.1): media renderers with grouping. Pairing-free. */
public class SonosAdapter implements DeviceAdapter {

    public static final String ID = SonosSettings.ADAPTER_ID;

    private final SonosProperties properties;
    private final SonosDiscovery discovery;
    private final HttpClient http;
    private final Map<String, SonosSession> sessions = new ConcurrentHashMap<>();

    public SonosAdapter(SonosProperties properties, SonosDiscovery discovery) {
        this.properties = properties;
        this.discovery = discovery;
        this.http = SoapClient.httpClient(Duration.ofSeconds(properties.connectTimeoutSeconds()));
        // A player that announces itself is back: skip the backoff.
        discovery.onAlive(uuid -> sessions.values().stream()
                .filter(session -> uuid != null && uuid.equals(session.uuid()))
                .forEach(SonosSession::reconnectNow));
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public DeviceKind kind() {
        return DeviceKind.SONOS;
    }

    @Override
    public Set<Capability> capabilities(Device device) {
        return EnumSet.of(Capability.MEDIA_RENDERER, Capability.VOLUME);
    }

    @Override
    public DeviceHandle connect(Device device, Consumer<DeviceState> onChange) {
        AtomicReference<SonosSession> self = new AtomicReference<>();
        SonosSession session = new SonosSession(device, properties, http, onChange,
                () -> sessions.remove(device.id(), self.get()));
        self.set(session);
        sessions.put(device.id(), session);
        session.start();
        return session;
    }

    @Override
    public List<DiscoveredDevice> discovered() {
        return discovery.devices();
    }

    @Override
    public Optional<Map<String, String>> settingsFor(DiscoveredDevice found) {
        return ID.equals(found.adapterId()) ? Optional.of(SonosSettings.from(found).toMap()) : Optional.empty();
    }
}

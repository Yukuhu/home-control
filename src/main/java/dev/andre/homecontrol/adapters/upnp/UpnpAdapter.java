package dev.andre.homecontrol.adapters.upnp;

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

/** UPnP/DLNA media renderers (spec §4.1): TVs, AV receivers, Wi-Fi speakers. Pairing-free. */
public class UpnpAdapter implements DeviceAdapter {

    public static final String ID = UpnpSettings.ADAPTER_ID;

    private final UpnpProperties properties;
    private final UpnpDiscovery discovery;
    private final HttpClient http;
    private final Map<String, UpnpSession> sessions = new ConcurrentHashMap<>();

    public UpnpAdapter(UpnpProperties properties, UpnpDiscovery discovery) {
        this.properties = properties;
        this.discovery = discovery;
        this.http = SoapClient.httpClient(Duration.ofSeconds(properties.connectTimeoutSeconds()));
        // A renderer that announces itself is back: skip the backoff.
        discovery.onAlive(udn -> sessions.values().stream()
                .filter(session -> udn != null && udn.equalsIgnoreCase(session.udn()))
                .forEach(UpnpSession::reconnectNow));
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public DeviceKind kind() {
        return DeviceKind.UPNP;
    }

    @Override
    public Set<Capability> capabilities(Device device) {
        return EnumSet.of(Capability.MEDIA_RENDERER, Capability.VOLUME);
    }

    @Override
    public DeviceHandle connect(Device device, Consumer<DeviceState> onChange) {
        AtomicReference<UpnpSession> self = new AtomicReference<>();
        // Only announcements from the registered address may point the session at a (new) description port.
        UpnpSession session = new UpnpSession(device, properties, http, udn -> discovery.location(udn, device.host()), onChange,
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
        return ID.equals(found.adapterId()) ? Optional.of(UpnpSettings.from(found).toMap()) : Optional.empty();
    }
}

package dev.andre.homecontrol.adapters.webos;

import dev.andre.homecontrol.adapters.net.InsecureTls;
import dev.andre.homecontrol.adapters.net.WakeOnLan;
import dev.andre.homecontrol.core.Capability;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceHandle;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.core.DeviceRegistry;
import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.DiscoveredDevice;
import dev.andre.homecontrol.core.LearnedSettings;
import dev.andre.homecontrol.core.WakeOnLanAdapter;
import dev.andre.homecontrol.discovery.ssdp.SsdpDiscovery;
import dev.andre.homecontrol.discovery.ssdp.SsdpService;

import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * LG webOS TVs over SSAP (spec §4.1). The client key lives in the device's adapter settings. The
 * registry is only read here; what a session learns is stored through the device manager.
 */
public class WebOsAdapter implements WakeOnLanAdapter {

    public static final String ID = WebOsSettings.ADAPTER_ID;
    public static final String SEARCH_TARGET = "urn:lge-com:service:webos-second-screen:1";

    private final WebOsProperties properties;
    private final SsdpDiscovery ssdp;
    private final DeviceRegistry registry;
    private final WakeOnLan wakeOnLan;
    private final HttpClient http;
    private final Map<String, WebOsSession> sessions = new ConcurrentHashMap<>();

    public WebOsAdapter(WebOsProperties properties, SsdpDiscovery ssdp, DeviceRegistry registry, WakeOnLan wakeOnLan) {
        this.properties = properties;
        this.ssdp = ssdp;
        this.registry = registry;
        this.wakeOnLan = wakeOnLan;
        this.http = InsecureTls.httpClient(Duration.ofSeconds(properties.connectTimeoutSeconds()));
        // A TV that just woke announces itself: reconnect now instead of waiting out the backoff.
        // Plain string comparison: SSDP listeners must not block on DNS.
        ssdp.addListener(SEARCH_TARGET, service -> sessions.values().stream()
                .filter(session -> session.host().equalsIgnoreCase(service.address()))
                .forEach(WebOsSession::reconnectNow));
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public DeviceKind kind() {
        return DeviceKind.WEBOS;
    }

    @Override
    public Set<Capability> capabilities(Device device) {
        return EnumSet.of(Capability.REMOTE_KEYS, Capability.POWER, Capability.VOLUME, Capability.APP_LINK);
    }

    /** Outside the device manager: the session works, but a learned MAC address or key is not stored. */
    @Override
    public DeviceHandle connect(Device device, Consumer<DeviceState> onChange) {
        return connect(device, onChange, LearnedSettings.DISCARD);
    }

    @Override
    public DeviceHandle connect(Device device, Consumer<DeviceState> onChange, LearnedSettings learned) {
        AtomicReference<WebOsSession> self = new AtomicReference<>();
        WebOsSession session = new WebOsSession(device, properties, http, registry, learned, wakeOnLan, onChange,
                () -> sessions.remove(device.id(), self.get()));
        self.set(session);
        sessions.put(device.id(), session);
        session.start();
        return session;
    }

    @Override
    public List<DiscoveredDevice> discovered() {
        return ssdp.services(SEARCH_TARGET).stream()
                .map(service -> new DiscoveredDevice(ID, name(service), service.address(), properties.port()))
                .toList();
    }

    static String name(SsdpService service) {
        return service.friendlyName()
                .or(() -> Optional.ofNullable(service.headers().get("DLNADeviceName.lge.com"))
                        .map(value -> URLDecoder.decode(value, StandardCharsets.UTF_8)))
                .orElse("LG webOS TV");
    }
}

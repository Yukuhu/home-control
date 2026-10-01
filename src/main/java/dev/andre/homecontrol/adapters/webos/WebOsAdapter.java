package dev.andre.homecontrol.adapters.webos;

import dev.andre.homecontrol.adapters.net.InsecureTls;
import dev.andre.homecontrol.adapters.net.WakeOnLan;
import dev.andre.homecontrol.adapters.support.PairingKeys;
import dev.andre.homecontrol.adapters.support.SessionRegistry;
import dev.andre.homecontrol.core.AdapterDiscovery;
import dev.andre.homecontrol.core.Capability;
import dev.andre.homecontrol.core.ForegroundAppReporting;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceAdapter;
import dev.andre.homecontrol.core.DeviceHandle;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.core.DeviceRegistry;
import dev.andre.homecontrol.core.DeviceSecrets;
import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.DiscoveredDevice;
import dev.andre.homecontrol.core.LearnedSettings;
import dev.andre.homecontrol.discovery.ssdp.SsdpDiscovery;
import dev.andre.homecontrol.discovery.ssdp.SsdpService;

import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * LG webOS TVs over SSAP (spec §4.1). The client key is a device secret, named by a reference in the device's adapter
 * settings. The registry is only read here; what a session learns is stored through the device package.
 */
public class WebOsAdapter implements DeviceAdapter, AdapterDiscovery {

    public static final String ADAPTER_ID = WebOsSettings.ADAPTER_ID;
    public static final String SEARCH_TARGET = "urn:lge-com:service:webos-second-screen:1";

    private final WebOsProperties properties;
    private final SsdpDiscovery ssdp;
    private final DeviceRegistry registry;
    private final WakeOnLan wakeOnLan;
    private final DeviceSecrets secrets;
    private final PairingKeys keys;
    private final HttpClient http;
    private final SessionRegistry<WebOsSession> sessions = new SessionRegistry<>();

    public WebOsAdapter(WebOsProperties properties, SsdpDiscovery ssdp, DeviceRegistry registry, WakeOnLan wakeOnLan,
                        DeviceSecrets secrets) {
        this.properties = properties;
        this.ssdp = ssdp;
        this.registry = registry;
        this.wakeOnLan = wakeOnLan;
        this.secrets = secrets;
        this.keys = WebOsSettings.keys(secrets);
        this.http = InsecureTls.httpClient(properties.connectTimeout());
        // A TV that just woke announces itself: reconnect now instead of waiting out the backoff.
        // Plain string comparison: SSDP listeners must not block on DNS.
        ssdp.addListener(SEARCH_TARGET, service -> sessions
                .matching(session -> session.host().equalsIgnoreCase(service.address()))
                .forEach(WebOsSession::reconnectNow));
    }

    @Override
    public String id() {
        return ADAPTER_ID;
    }

    @Override
    public DeviceKind kind() {
        return DeviceKind.WEBOS;
    }

    /** The getForegroundAppInfo subscription pushes every change. */
    @Override
    public ForegroundAppReporting foregroundAppReporting(Device device) {
        return ForegroundAppReporting.LIVE;
    }

    @Override
    public Set<Capability> capabilities(Device device) {
        return EnumSet.of(Capability.REMOTE_KEYS, Capability.APP_LINK, Capability.VOLUME, Capability.INPUTS,
                Capability.WAKE_ON_LAN);
    }

    /** Outside the device package: the session works, but a learned MAC address or key is not stored. */
    @Override
    public DeviceHandle connect(Device device, Consumer<DeviceState> onChange) {
        return connect(device, onChange, LearnedSettings.DISCARD);
    }

    @Override
    public DeviceHandle connect(Device device, Consumer<DeviceState> onChange, LearnedSettings learned) {
        WebOsSession session = sessions.open(device.id(), onClose -> new WebOsSession(device, properties,
                WebOsTimings.from(properties), http, registry, learned, secrets, wakeOnLan, onChange, onClose));
        session.start();
        return session;
    }

    /** Moves a client key still in devices.json into a device secret. Idempotent, and safe to rerun after a crash. */
    @Override
    public Device migrate(Device device) {
        return keys.migrate(device, WebOsSettings.LEGACY_KEY_FIELD);
    }

    @Override
    public void forget(Device device) {
        keys.forget(device, registry.findAll());
    }

    @Override
    public List<DiscoveredDevice> discovered() {
        return ssdp.services(SEARCH_TARGET).stream()
                .map(service -> new DiscoveredDevice(ADAPTER_ID, name(service), service.address(), properties.port()))
                .toList();
    }

    static String name(SsdpService service) {
        return service.friendlyName()
                .or(() -> Optional.ofNullable(service.headers().get("DLNADeviceName.lge.com"))
                        .map(value -> URLDecoder.decode(value, StandardCharsets.UTF_8)))
                .orElse("LG webOS TV");
    }
}

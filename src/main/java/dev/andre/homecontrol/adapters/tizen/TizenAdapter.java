package dev.andre.homecontrol.adapters.tizen;

import dev.andre.homecontrol.adapters.net.InsecureTls;
import dev.andre.homecontrol.adapters.net.WakeOnLan;
import dev.andre.homecontrol.adapters.support.PairingKeys;
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

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Samsung Tizen TVs: remote-control WebSocket, REST API and DIAL (spec §4.1). The registry is only
 * read here; what a session learns (token, MAC) is stored through the device package.
 */
public class TizenAdapter implements DeviceAdapter, AdapterDiscovery {

    public static final String ADAPTER_ID = TizenSettings.ADAPTER_ID;
    public static final String SEARCH_TARGET = "urn:samsung.com:device:RemoteControlReceiver:1";

    private final TizenProperties properties;
    private final SsdpDiscovery ssdp;
    private final DeviceRegistry registry;
    private final WakeOnLan wakeOnLan;
    private final DeviceSecrets secrets;
    private final PairingKeys keys;
    private final HttpClient http;
    private final Map<String, TizenSession> sessions = new ConcurrentHashMap<>();

    public TizenAdapter(TizenProperties properties, SsdpDiscovery ssdp, DeviceRegistry registry, WakeOnLan wakeOnLan,
                        DeviceSecrets secrets) {
        this.properties = properties;
        this.ssdp = ssdp;
        this.registry = registry;
        this.wakeOnLan = wakeOnLan;
        this.secrets = secrets;
        this.keys = TizenSettings.keys(secrets);
        this.http = InsecureTls.httpClient(properties.connectTimeout());
        // A TV that just woke announces itself: poll now instead of at the next interval.
        // Plain string comparison: SSDP listeners must not block on DNS.
        ssdp.addListener(SEARCH_TARGET, service -> sessions.values().stream()
                .filter(session -> session.host().equalsIgnoreCase(service.address()))
                .forEach(TizenSession::pollNow));
    }

    @Override
    public String id() {
        return ADAPTER_ID;
    }

    @Override
    public DeviceKind kind() {
        return DeviceKind.TIZEN;
    }

    /** Polled from the REST applications endpoint, and only for YouTube, Netflix and Prime Video. */
    @Override
    public ForegroundAppReporting foregroundAppReporting(Device device) {
        return ForegroundAppReporting.POLLED;
    }

    @Override
    public Set<Capability> capabilities(Device device) {
        return EnumSet.of(Capability.REMOTE_KEYS, Capability.APP_LINK, Capability.WAKE_ON_LAN);
    }

    /** Outside the device package: the session works, but a learned MAC address or token is not stored. */
    @Override
    public DeviceHandle connect(Device device, Consumer<DeviceState> onChange) {
        return connect(device, onChange, LearnedSettings.DISCARD);
    }

    @Override
    public DeviceHandle connect(Device device, Consumer<DeviceState> onChange, LearnedSettings learned) {
        AtomicReference<TizenSession> self = new AtomicReference<>();
        TizenSession session = new TizenSession(device, properties, TizenTimings.from(properties), http, registry,
                learned, secrets, wakeOnLan, onChange, () -> sessions.remove(device.id(), self.get()));
        self.set(session);
        sessions.put(device.id(), session);
        session.start();
        return session;
    }

    /** Moves a token still in devices.json into a device secret. Idempotent, and safe to rerun after a crash. */
    @Override
    public Device migrate(Device device) {
        return keys.migrate(device, TizenSettings.LEGACY_TOKEN);
    }

    @Override
    public void forget(Device device) {
        keys.forget(device, registry.findAll());
    }

    @Override
    public List<DiscoveredDevice> discovered() {
        return ssdp.services(SEARCH_TARGET).stream()
                .map(service -> new DiscoveredDevice(ADAPTER_ID, service.friendlyName().orElse("Samsung TV"),
                        service.address(), properties.port()))
                .toList();
    }
}

package dev.andre.homecontrol.adapters.sonos;

import dev.andre.homecontrol.adapters.sonos.protocol.SonosActions;
import dev.andre.homecontrol.adapters.sonos.protocol.SonosEndpoints;
import dev.andre.homecontrol.adapters.sonos.protocol.ZoneGroupState;
import dev.andre.homecontrol.adapters.upnp.protocol.SoapClient;
import dev.andre.homecontrol.adapters.upnp.protocol.SoapFault;
import dev.andre.homecontrol.core.DeviceDiscoveredEvent;
import dev.andre.homecontrol.core.DiscoveredDevice;
import dev.andre.homecontrol.discovery.ssdp.DeviceFetch;
import dev.andre.homecontrol.discovery.ssdp.SsdpDiscovery;
import dev.andre.homecontrol.discovery.ssdp.SsdpService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/**
 * Sonos rooms: any announcing player tells us its whole household through GetZoneGroupState, asked
 * over the capped {@link SoapClient} at the address the announcement came from; rooms outside the LAN
 * are dropped by {@link ZoneGroupState}.
 */
public class SonosDiscovery implements AutoCloseable {

    public static final String SEARCH_TARGET = "urn:schemas-upnp-org:device:ZonePlayer:1";
    private static final Logger log = LoggerFactory.getLogger(SonosDiscovery.class);

    private final SsdpDiscovery ssdp;
    private final SonosProperties properties;
    private final ApplicationEventPublisher events;
    private final SoapClient soap;
    private final Clock clock;
    /** household → (uuid → room) */
    private final Map<String, Map<String, DiscoveredDevice>> households = new ConcurrentHashMap<>();
    private final Map<String, Instant> refreshedAt = new ConcurrentHashMap<>();
    private final List<Consumer<String>> aliveListeners = new CopyOnWriteArrayList<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("sonos-discovery").factory());

    public SonosDiscovery(SsdpDiscovery ssdp, SonosProperties properties, ApplicationEventPublisher events) {
        this(ssdp, properties, events, Clock.systemUTC());
    }

    SonosDiscovery(SsdpDiscovery ssdp, SonosProperties properties, ApplicationEventPublisher events, Clock clock) {
        this.ssdp = ssdp;
        this.properties = properties;
        this.events = events;
        this.clock = clock;
        this.soap = new SoapClient(SoapClient.httpClient(Duration.ofSeconds(properties.connectTimeoutSeconds())),
                Duration.ofSeconds(properties.commandTimeoutSeconds()));
        ssdp.addListener(SEARCH_TARGET, service -> submit(() -> seen(service)));
    }

    public List<DiscoveredDevice> devices() {
        return households.values().stream().flatMap(rooms -> rooms.values().stream())
                .sorted(Comparator.comparing(DiscoveredDevice::name, String.CASE_INSENSITIVE_ORDER)).toList();
    }

    public void onAlive(Consumer<String> uuidListener) {
        aliveListeners.add(uuidListener);
    }

    void seen(SsdpService service) {
        String uuid = uuidOf(service.usn());
        aliveListeners.forEach(listener -> listener.accept(uuid));
        String household = service.headers().getOrDefault("X-RINCON-HOUSEHOLD", "default");
        Instant last = refreshedAt.getOrDefault(household, Instant.EPOCH);
        boolean known = households.getOrDefault(household, Map.of()).containsKey(uuid);
        if (known && Duration.between(last, clock.instant()).toSeconds() < properties.topologyIntervalSeconds()) {
            return;
        }
        String address = service.address();
        if (!DeviceFetch.isSafeToFetch(service.location(), address)) {
            // F1's rule: only ever call the address the datagram came from (the LOCATION itself is not logged).
            log.debug("Sonos announcement from {} names another location; not asking it", address);
            return;
        }
        int port = service.location().getPort();
        try {
            String xml = soap.call(SonosEndpoints.endpoint(address, port, SonosEndpoints.ZONE_GROUP_TOPOLOGY_PATH,
                    SonosEndpoints.ZONE_GROUP_TOPOLOGY).controlUrl(), SonosActions.getZoneGroupState()).getOrDefault("ZoneGroupState", "");
            List<DiscoveredDevice> rooms = toDevices(ZoneGroupState.parse(xml, SonosEndpoints.isLoopback(address)));
            refreshedAt.put(household, clock.instant());
            Map<String, DiscoveredDevice> previous = households.getOrDefault(household, Map.of());
            Map<String, DiscoveredDevice> current = new LinkedHashMap<>();
            rooms.forEach(room -> current.put(room.attributes().get(SonosSettings.UUID), room));
            households.put(household, current);
            current.forEach((id, room) -> {
                if (!room.equals(previous.get(id))) {
                    log.info("Discovered Sonos room {} at {}", room.name(), room.host());
                    // A player speaks only for itself: the other rooms it lists are shown on Setup, but only the
                    // room at the announcing address, under the announced id, may merge into or re-point a
                    // registered device (DeviceManager.onDiscovered) — a forged household cannot rewrite others.
                    if (id.equals(uuid) && room.host().equals(address)) {
                        events.publishEvent(new DeviceDiscoveredEvent(room));
                    }
                }
            });
        } catch (IOException | SoapFault | IllegalArgumentException e) {
            log.debug("No zone group state from {}: {}", address, e.getMessage());
        }
    }

    static List<DiscoveredDevice> toDevices(ZoneGroupState state) {
        return state.visibleMembers().stream()
                .map(member -> new DiscoveredDevice(SonosSettings.ADAPTER_ID, member.zoneName(), member.host(), member.port(),
                        Map.of(SonosSettings.UUID, member.uuid())))
                .toList();
    }

    static String uuidOf(String usn) {
        String withoutPrefix = usn.startsWith("uuid:") ? usn.substring(5) : usn;
        int separator = withoutPrefix.indexOf("::");
        return separator < 0 ? withoutPrefix : withoutPrefix.substring(0, separator);
    }

    private void submit(Runnable task) {
        try {
            worker.execute(task);
        } catch (RejectedExecutionException ignored) {
            // closing
        }
    }

    @Override
    public void close() {
        worker.shutdownNow();
    }
}

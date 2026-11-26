package dev.andre.homecontrol.adapters.sonos;

import dev.andre.homecontrol.adapters.sonos.protocol.SonosActions;
import dev.andre.homecontrol.adapters.sonos.protocol.SonosEndpoints;
import dev.andre.homecontrol.adapters.sonos.protocol.ZoneGroupState;
import dev.andre.homecontrol.adapters.upnp.protocol.SoapClient;
import dev.andre.homecontrol.adapters.upnp.protocol.SoapFault;
import dev.andre.homecontrol.core.DeviceDiscoveredEvent;
import dev.andre.homecontrol.core.DiscoveredDevice;
import dev.andre.homecontrol.discovery.ssdp.SsdpDiscovery;
import dev.andre.homecontrol.discovery.ssdp.SsdpService;
import dev.andre.homecontrol.discovery.ssdp.protocol.DeviceFetch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 *
 * <p>The household an announcement names is whatever its sender put in it, so what it can make this class keep is
 * bounded: announcements from one address wait as one, an address is asked at most once per topology interval, at
 * most {@link #MAX_HOUSEHOLDS} households are kept, and an answer listing more than {@link #MAX_ROOMS} rooms is
 * ignored.
 */
public class SonosDiscovery implements AutoCloseable {

    public static final String SEARCH_TARGET = "urn:schemas-upnp-org:device:ZonePlayer:1";
    /** Households kept at once; a home has one, rarely two. The one refreshed longest ago makes room for a new one. */
    static final int MAX_HOUSEHOLDS = 16;
    /** Rooms one household may list; Sonos allows 32 players in a household. */
    static final int MAX_ROOMS = 64;
    /** Addresses whose last question is remembered; beyond it, those asked longer ago than the interval are dropped. */
    private static final int MAX_ASKED = 1024;
    private static final Logger log = LoggerFactory.getLogger(SonosDiscovery.class);

    private final SsdpDiscovery ssdp;
    private final SonosProperties properties;
    private final ApplicationEventPublisher events;
    private final SoapClient soap;
    private final Clock clock;
    /** household → (uuid → room) */
    private final Map<String, Map<String, DiscoveredDevice>> households = new ConcurrentHashMap<>();
    private final Map<String, Instant> refreshedAt = new ConcurrentHashMap<>();
    /** When each address was last asked for its household; worker thread only. */
    private final Map<String, Instant> askedAt = new HashMap<>();
    /** Addresses with an announcement waiting for the worker; later ones from the same address are dropped. */
    private final Set<String> queued = ConcurrentHashMap.newKeySet();
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
        this.soap = new SoapClient(SoapClient.httpClient(properties.connectTimeout()),
                properties.commandTimeout());
        ssdp.addListener(SEARCH_TARGET, this::announced);
    }

    public List<DiscoveredDevice> devices() {
        return households.values().stream().flatMap(rooms -> rooms.values().stream())
                .sorted(Comparator.comparing(DiscoveredDevice::name, String.CASE_INSENSITIVE_ORDER)).toList();
    }

    public void onAlive(Consumer<String> uuidListener) {
        aliveListeners.add(uuidListener);
    }

    /** One announcement waits for the worker per address: a flood of them from one sender queues as one. */
    private void announced(SsdpService service) {
        String address = service.address();
        if (queued.add(address)) {
            submit(() -> {
                queued.remove(address);
                seen(service);
            });
        }
    }

    void seen(SsdpService service) {
        String uuid = uuidOf(service.usn());
        aliveListeners.forEach(listener -> listener.accept(uuid));
        String household = service.headers().getOrDefault("X-RINCON-HOUSEHOLD", "default");
        Instant now = clock.instant();
        Instant last = refreshedAt.getOrDefault(household, Instant.EPOCH);
        boolean known = households.getOrDefault(household, Map.of()).containsKey(uuid);
        if (known && recent(last, now)) {
            return;
        }
        String address = service.address();
        // A player is asked at most once per interval, whatever household its announcements name.
        if (recent(askedAt.getOrDefault(address, Instant.EPOCH), now)) {
            return;
        }
        if (!DeviceFetch.isSafeToFetch(service.location(), address)) {
            // F1's rule: only ever call the address the datagram came from (the LOCATION itself is not logged).
            log.debug("Sonos announcement from {} names another location; not asking it", address);
            return;
        }
        int port = service.location().getPort();
        asked(address, now);
        try {
            String xml = soap.call(SonosEndpoints.endpoint(address, port, SonosEndpoints.ZONE_GROUP_TOPOLOGY_PATH,
                    SonosEndpoints.ZONE_GROUP_TOPOLOGY).controlUrl(), SonosActions.getZoneGroupState()).getOrDefault("ZoneGroupState", "");
            List<DiscoveredDevice> rooms = toDevices(ZoneGroupState.parse(xml, SonosEndpoints.isLoopback(address)));
            if (rooms.size() > MAX_ROOMS) {
                log.debug("Sonos announcement from {} lists {} rooms; ignoring it", address, rooms.size());
                return;
            }
            makeRoomFor(household);
            refreshedAt.put(household, clock.instant());
            Map<String, DiscoveredDevice> previous = households.getOrDefault(household, Map.of());
            Map<String, DiscoveredDevice> current = new LinkedHashMap<>();
            rooms.forEach(room -> current.put(room.attributes().get(SonosSettings.UUID_KEY), room));
            households.put(household, current);
            current.forEach((id, room) -> {
                if (!room.equals(previous.get(id))) {
                    log.info("Discovered Sonos room {} at {}", room.name(), room.host());
                    // A player speaks only for itself: the other rooms it lists are shown on Setup, but only the
                    // room at the announcing address, under the announced id, may merge into or re-point a
                    // registered device (Devices.onDiscovered) — a forged household cannot rewrite others.
                    if (id.equals(uuid) && room.host().equals(address)) {
                        events.publishEvent(new DeviceDiscoveredEvent(room));
                    }
                }
            });
        } catch (IOException | SoapFault | IllegalArgumentException e) {
            log.debug("No zone group state from {}: {}", address, e.getMessage());
        }
    }

    private boolean recent(Instant then, Instant now) {
        return Duration.between(then, now).compareTo(properties.topologyInterval()) < 0;
    }

    private void asked(String address, Instant now) {
        if (askedAt.size() >= MAX_ASKED) {
            askedAt.values().removeIf(then -> !recent(then, now));
        }
        if (askedAt.size() < MAX_ASKED || askedAt.containsKey(address)) {
            askedAt.put(address, now);
        }
    }

    /** Drops the household refreshed longest ago when a new one would be one too many. */
    private void makeRoomFor(String household) {
        if (households.containsKey(household) || households.size() < MAX_HOUSEHOLDS) {
            return;
        }
        refreshedAt.entrySet().stream()
                .filter(entry -> households.containsKey(entry.getKey()))
                .min(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .ifPresent(oldest -> {
                    households.remove(oldest);
                    refreshedAt.remove(oldest);
                });
    }

    static List<DiscoveredDevice> toDevices(ZoneGroupState state) {
        return state.visibleMembers().stream()
                .map(member -> new DiscoveredDevice(SonosSettings.ADAPTER_ID, member.zoneName(), member.host(), member.port(),
                        Map.of(SonosSettings.UUID_KEY, member.uuid())))
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
        } catch (RejectedExecutionException _) {
            // closing
        }
    }

    @Override
    public void close() {
        worker.shutdownNow();
    }
}

package dev.andre.homecontrol.discovery.ssdp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The one SSDP listener every UPnP-style adapter shares (spec §7): webOS and Tizen TVs now,
 * DLNA renderers and Sonos in sub-project I. Adapters {@link #watch} their search targets;
 * this service searches for them periodically, listens for NOTIFY announcements, keeps each
 * service until its max-age runs out or it says byebye, and fetches its description once.
 *
 * <p>Multicast does not cross a Docker bridge network, so every adapter also accepts an
 * address typed in by hand.
 */
public class SsdpDiscovery implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SsdpDiscovery.class);
    private static final String USER_AGENT = DeviceFetch.USER_AGENT;

    private final SsdpProperties properties;
    private final Clock clock;
    private final HttpClient http;
    private final Set<String> watched = ConcurrentHashMap.newKeySet();
    private final Map<String, SsdpService> services = new ConcurrentHashMap<>();
    private final Map<String, List<SsdpListener>> listeners = new ConcurrentHashMap<>();

    private volatile boolean running;
    private volatile DatagramSocket searchSocket;
    private volatile MulticastSocket notifySocket;
    private volatile ScheduledExecutorService scheduler;

    public SsdpDiscovery(SsdpProperties properties) {
        // Embedded UPnP servers reject the "Upgrade: h2c" header the JDK sends by default; never follow redirects.
        this(properties, Clock.systemUTC(), HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(3)).build());
    }

    SsdpDiscovery(SsdpProperties properties, Clock clock, HttpClient http) {
        this.properties = properties;
        this.clock = clock;
        this.http = http;
    }

    public void start() {
        if (!properties.enabled()) {
            log.info("SSDP discovery is disabled; add smart TVs by address");
            return;
        }
        running = true;
        try {
            searchSocket = new DatagramSocket(0);
            Thread.ofVirtual().name("ssdp-responses").start(() -> receive(searchSocket));
        } catch (IOException e) {
            log.warn("SSDP search is unavailable ({}); add smart TVs by address", e.getMessage());
        }
        try {
            MulticastSocket socket = new MulticastSocket(null);
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(properties.listenPort()));
            InetAddress group = InetAddress.getByName(properties.multicastAddress());
            if (group.isMulticastAddress()) {
                joinEverywhere(socket, group);
            }
            notifySocket = socket;
            Thread.ofVirtual().name("ssdp-notify").start(() -> receive(socket));
        } catch (IOException e) {
            log.warn("Not listening for SSDP announcements ({}); periodic searches still run", e.getMessage());
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("ssdp-search").factory());
        scheduler.scheduleWithFixedDelay(this::searchAll, 0, properties.searchIntervalSeconds(), TimeUnit.SECONDS);
    }

    /** Start looking for {@code searchTarget}; searches for it at once if discovery is running. */
    public void watch(String searchTarget) {
        if (watched.add(searchTarget) && running && scheduler != null) {
            scheduler.execute(() -> search(searchTarget));
        }
    }

    public void addListener(String searchTarget, SsdpListener listener) {
        watch(searchTarget);
        listeners.computeIfAbsent(searchTarget, key -> new CopyOnWriteArrayList<>()).add(listener);
    }

    /** Live services of one target, ordered by address. Expired entries are dropped on read. */
    public List<SsdpService> services(String searchTarget) {
        Instant now = clock.instant();
        services.values().removeIf(service -> !service.expiresAt().isAfter(now));
        return services.values().stream()
                .filter(service -> service.type().equals(searchTarget))
                .sorted(Comparator.comparing(SsdpService::address))
                .toList();
    }

    /** The port NOTIFY datagrams are received on, or -1 when not listening. */
    public int listenPort() {
        MulticastSocket socket = notifySocket;
        return socket == null ? -1 : socket.getLocalPort();
    }

    private void searchAll() {
        watched.forEach(this::search);
    }

    private void search(String searchTarget) {
        DatagramSocket socket = searchSocket;
        if (socket == null) {
            return;
        }
        byte[] request = SsdpMessage.search(searchTarget,
                properties.multicastAddress() + ":" + properties.port(), properties.mx(), USER_AGENT);
        DatagramPacket packet = new DatagramPacket(request, request.length,
                new InetSocketAddress(properties.multicastAddress(), properties.port()));
        try {
            // UDP is lossy; UPnP recommends sending each search more than once.
            socket.send(packet);
            socket.send(packet);
        } catch (IOException e) {
            log.debug("SSDP search for {} failed: {}", searchTarget, e.getMessage());
        }
    }

    private void joinEverywhere(MulticastSocket socket, InetAddress group) throws IOException {
        int joined = 0;
        for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            try {
                if (nic.isUp() && nic.supportsMulticast() && !nic.isLoopback()) {
                    socket.joinGroup(new InetSocketAddress(group, 0), nic);
                    joined++;
                }
            } catch (IOException e) {
                log.debug("Cannot join {} on {}: {}", group, nic.getName(), e.getMessage());
            }
        }
        if (joined == 0) {
            socket.joinGroup(new InetSocketAddress(group, 0), null);
        }
    }

    private void receive(DatagramSocket socket) {
        byte[] buffer = new byte[8192];
        while (running && !socket.isClosed()) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(packet);
            } catch (IOException e) {
                if (running && !socket.isClosed()) {
                    log.debug("SSDP receive failed: {}", e.getMessage());
                    continue;
                }
                return;
            }
            InetAddress sender = packet.getAddress();
            SsdpMessage.parse(packet.getData(), packet.getLength()).ifPresent(message -> handle(message, sender));
        }
    }

    void handle(SsdpMessage message, InetAddress sender) {
        if (message.kind() == SsdpMessage.Kind.SEARCH_REQUEST) {
            return;
        }
        Optional<String> type = message.type();
        Optional<String> usn = message.header("USN");
        if (type.isEmpty() || usn.isEmpty() || !watched.contains(type.get())) {
            return;
        }
        if (message.isByeBye()) {
            SsdpService gone = services.remove(usn.get());
            if (gone != null) {
                listenersOf(gone.type()).forEach(listener -> listener.byebye(gone));
            }
            return;
        }
        URI location = message.header("LOCATION").flatMap(SsdpDiscovery::toUri).orElse(null);
        // The service's address is always where the datagram actually came from — never a claim
        // inside the (unauthenticated) payload — so a forged LOCATION can never redirect a device
        // entry, let alone the description fetch below, anywhere but where it was heard from.
        String address = sender.getHostAddress();
        SsdpService previous = services.get(usn.get());
        DeviceDescription known = previous != null && Objects.equals(previous.location(), location)
                ? previous.description() : null;
        SsdpService seen = new SsdpService(usn.get(), type.get(), address, location, message.headers(),
                clock.instant().plus(message.maxAge()), known);
        services.put(usn.get(), seen);
        if (known == null && location != null) {
            if (DeviceFetch.isSafeToFetch(location, sender)) {
                Thread.ofVirtual().name("ssdp-describe").start(() -> describe(seen));
            } else {
                // Deliberately omit the LOCATION value itself: it is attacker-controlled and this
                // is exactly the case where we do not want it dignified by ending up in a log sink.
                log.debug("LOCATION for {} does not match its announcing address; not fetching its description",
                        usn.get());
            }
        }
        listenersOf(seen.type()).forEach(listener -> listener.alive(seen));
    }

    private List<SsdpListener> listenersOf(String type) {
        return listeners.getOrDefault(type, List.of());
    }

    /** Fetched only when {@link DeviceFetch#isSafeToFetch} held for the announcing datagram. */
    private void describe(SsdpService service) {
        try {
            byte[] bytes = DeviceFetch.get(http, service.location(), Duration.ofSeconds(3), DeviceFetch.MAX_DESCRIPTION_BYTES);
            DeviceDescription description = DeviceDescriptions.parse(bytes, service.location());
            services.computeIfPresent(service.usn(), (usn, current) -> current.withDescription(description));
        } catch (IOException | IllegalArgumentException e) {
            log.debug("No description for {}: {}", service.usn(), e.getMessage());
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    private static Optional<URI> toUri(String value) {
        try {
            return Optional.of(URI.create(value));
        } catch (IllegalArgumentException _) {
            return Optional.empty();
        }
    }

    @Override
    public void close() {
        running = false;
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        if (searchSocket != null) {
            searchSocket.close();
        }
        if (notifySocket != null) {
            notifySocket.close();
        }
    }
}

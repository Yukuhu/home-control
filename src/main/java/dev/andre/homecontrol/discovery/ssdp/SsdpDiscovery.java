package dev.andre.homecontrol.discovery.ssdp;

import dev.andre.homecontrol.discovery.ssdp.protocol.DeviceDescription;
import dev.andre.homecontrol.discovery.ssdp.protocol.DeviceDescriptions;
import dev.andre.homecontrol.discovery.ssdp.protocol.DeviceFetch;
import dev.andre.homecontrol.discovery.ssdp.protocol.SsdpMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

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
import java.util.concurrent.Semaphore;
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
    /** Any host on the LAN can announce any number of services; at most this many are kept. */
    static final int MAX_SERVICES = 256;
    /** At most this many of them from one host, so that one host cannot fill discovery with services it invents. */
    static final int MAX_SERVICES_PER_HOST = 32;
    /** Description fetches at once; a service announced meanwhile is fetched at a later announcement. */
    static final int MAX_FETCHES = 4;

    private final SsdpProperties properties;
    private final SsdpTimings timings;
    private final Clock clock;
    private final HttpClient http;
    private final Set<String> watched = ConcurrentHashMap.newKeySet();
    private final Map<String, SsdpService> services = new ConcurrentHashMap<>();
    /** When each kept service was last heard: what a full discovery makes room by, not a lifetime its sender chose. */
    private final Map<String, Instant> heard = new ConcurrentHashMap<>();
    private final Map<String, List<SsdpListener>> listeners = new ConcurrentHashMap<>();
    /** USNs whose description is being fetched, so that their next announcements do not fetch it again. */
    private final Set<String> fetching = ConcurrentHashMap.newKeySet();
    private final Semaphore fetches = new Semaphore(MAX_FETCHES);

    private volatile boolean running;
    // Assigned once in start() and never replaced; the socket is thread-safe, volatile only publishes it.
    @SuppressWarnings("java:S3077")
    private volatile DatagramSocket searchSocket;
    // Assigned once in start() and never replaced; the socket is thread-safe, volatile only publishes it.
    @SuppressWarnings("java:S3077")
    private volatile MulticastSocket notifySocket;
    // Assigned once in start() and never replaced; the executor is thread-safe, volatile only publishes it.
    @SuppressWarnings("java:S3077")
    private volatile ScheduledExecutorService scheduler;

    public SsdpDiscovery(SsdpProperties properties) {
        this(properties, SsdpTimings.from(properties));
    }

    public SsdpDiscovery(SsdpProperties properties, SsdpTimings timings) {
        // Embedded UPnP servers reject the "Upgrade: h2c" header the JDK sends by default; never follow redirects.
        this(properties, timings, Clock.systemUTC(), HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(3)).build());
    }

    SsdpDiscovery(SsdpProperties properties, SsdpTimings timings, Clock clock, HttpClient http) {
        this.properties = properties;
        this.timings = timings;
        this.clock = clock;
        this.http = http;
    }

    /** Starts listening once the application is ready, so no discovery event reaches the context before its listeners exist. */
    @EventListener(ApplicationReadyEvent.class)
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
        scheduler.scheduleWithFixedDelay(this::searchAll, 0, timings.searchInterval().toMillis(), TimeUnit.MILLISECONDS);
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
        heard.keySet().retainAll(services.keySet());
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
            try {
                SsdpMessage.parse(packet.getData(), packet.getLength()).ifPresent(message -> handle(message, sender));
            } catch (RuntimeException e) {
                // Neither a datagram nor a listener may end discovery until the next restart: the next one is read.
                log.warn("Ignored an SSDP datagram from {} that could not be handled: {}", sender.getHostAddress(),
                        e.toString());
                log.debug("The SSDP datagram's failure", e);
            }
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
            heard.remove(usn.get());
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
        if (!keep(seen)) {
            return;
        }
        if (known == null && location != null) {
            if (DeviceFetch.isSafeToFetch(location, sender)) {
                fetchDescription(seen);
            } else {
                // Deliberately omit the LOCATION value itself: it is attacker-controlled and this
                // is exactly the case where we do not want it dignified by ending up in a log sink.
                log.debug("LOCATION for {} does not match its announcing address; not fetching its description",
                        usn.get());
            }
        }
        listenersOf(seen.type()).forEach(listener -> listener.alive(seen));
    }

    /**
     * Keeps a service just heard, unless it is new and its host already has its share. One at a time: both receive
     * loops call this, and the caps hold only if no other insertion slips between a check and its insertion.
     */
    private synchronized boolean keep(SsdpService seen) {
        if (!services.containsKey(seen.usn())) {
            if (keptFrom(seen.address()) >= MAX_SERVICES_PER_HOST) {
                log.debug("{} announces more than {} services; not keeping {}", seen.address(), MAX_SERVICES_PER_HOST,
                        seen.usn());
                return false;
            }
            makeRoom();
        }
        services.put(seen.usn(), seen);
        heard.put(seen.usn(), clock.instant());
        return true;
    }

    private long keptFrom(String address) {
        Instant now = clock.instant();
        return services.values().stream()
                .filter(service -> service.address().equals(address) && service.expiresAt().isAfter(now))
                .count();
    }

    /**
     * Below the cap, after the expired services; else without the one heard from longest ago. Not the one that would
     * expire soonest: a sender chooses its lifetime, while a real device answers every search and stays recent.
     */
    private synchronized void makeRoom() {
        if (services.size() < MAX_SERVICES) {
            return;
        }
        Instant now = clock.instant();
        services.values().removeIf(service -> !service.expiresAt().isAfter(now));
        heard.keySet().retainAll(services.keySet());
        while (services.size() >= MAX_SERVICES) {
            services.keySet().stream().min(Comparator.comparing(usn -> heard.getOrDefault(usn, Instant.MIN)))
                    .ifPresent(oldest -> {
                        services.remove(oldest);
                        heard.remove(oldest);
                    });
        }
    }

    /** Once per service at a time, and a few at once; one left out is fetched at a later announcement. */
    private void fetchDescription(SsdpService service) {
        if (!fetching.add(service.usn())) {
            return;
        }
        if (!fetches.tryAcquire()) {
            fetching.remove(service.usn());
            return;
        }
        Thread.ofVirtual().name("ssdp-describe").start(() -> {
            try {
                describe(service);
            } finally {
                fetches.release();
                fetching.remove(service.usn());
            }
        });
    }

    private List<SsdpListener> listenersOf(String type) {
        return listeners.getOrDefault(type, List.of());
    }

    /** Fetched only when {@link DeviceFetch#isSafeToFetch} held for the announcing datagram. */
    private void describe(SsdpService service) {
        try {
            byte[] bytes = DeviceFetch.get(http, service.location(), Duration.ofSeconds(3), DeviceFetch.MAX_DESCRIPTION_BYTES);
            DeviceDescription description = DeviceDescriptions.parse(bytes, service.location());
            // A service announced at a new LOCATION meanwhile keeps waiting for that one's description.
            services.computeIfPresent(service.usn(), (usn, current) -> Objects.equals(current.location(),
                    service.location()) ? current.withDescription(description) : current);
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

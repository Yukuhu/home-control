package dev.andre.homecontrol.discovery.ssdp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

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
    private static final String USER_AGENT = "Linux/1 UPnP/1.1 HomeControl/1";
    /** A device description is small XML; anything past this is refused rather than read into memory. */
    private static final long MAX_DESCRIPTION_BYTES = 64 * 1024;
    private static final Pattern IPV4_LITERAL = Pattern.compile(
            "^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$");

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
        this(properties, Clock.systemUTC(), HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
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
            if (isSafeToFetch(location, sender)) {
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

    /**
     * Only ever fetch a description from the address it was announced from (spec §1.1's LOCATION
     * is carried in an unauthenticated UDP payload, so trusting it as-is would let one forged
     * datagram make this appliance issue an HTTP GET to any URL of the sender's choosing — a
     * server-side request forgery into the LAN or, via a public multicast relay, further still).
     * {@code https} and other schemes are refused outright (no adapter's UPnP services use them);
     * the host must be an IP literal so nothing here ever triggers a DNS lookup driven by an
     * unauthenticated datagram, and that literal must equal the datagram's sender; the port must
     * be explicit, matching every real UPnP LOCATION.
     */
    private static boolean isSafeToFetch(URI location, InetAddress sender) {
        if (!"http".equalsIgnoreCase(location.getScheme())) {
            return false;
        }
        String host = location.getHost();
        if (host == null || !isIpLiteral(host) || location.getPort() <= 0) {
            return false;
        }
        try {
            return InetAddress.getByName(host).equals(sender);
        } catch (UnknownHostException e) {
            // Unreachable: an address that passed isIpLiteral never performs a lookup and never fails.
            return false;
        }
    }

    /** True only for a textual IPv4/IPv6 address — never a name that would need a DNS lookup to resolve. */
    private static boolean isIpLiteral(String host) {
        if (IPV4_LITERAL.matcher(host).matches()) {
            return true;
        }
        // An IPv6 literal (URI already strips the brackets) contains only hex digits, ':', '.'
        // (an embedded IPv4 tail) and '%' (a zone id) — never a letter outside a-f, so this can
        // never accidentally match a DNS hostname.
        return host.indexOf(':') >= 0 && host.chars().allMatch(c ->
                Character.isDigit(c) || c == ':' || c == '.' || c == '%' || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'));
    }

    private List<SsdpListener> listenersOf(String type) {
        return listeners.getOrDefault(type, List.of());
    }

    private void describe(SsdpService service) {
        try {
            HttpResponse<InputStream> response = http.send(
                    HttpRequest.newBuilder(service.location()).timeout(Duration.ofSeconds(3)).GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                if (response.statusCode() != 200) {
                    return;
                }
                OptionalLong contentLength = contentLength(response);
                if (contentLength.isPresent() && contentLength.getAsLong() > MAX_DESCRIPTION_BYTES) {
                    log.debug("Description for {} declares {} bytes, over the {}-byte cap; ignoring",
                            service.usn(), contentLength.getAsLong(), MAX_DESCRIPTION_BYTES);
                    return;
                }
                byte[] bytes = readAtMost(body, MAX_DESCRIPTION_BYTES);
                if (bytes == null) {
                    log.debug("Description for {} exceeds the {}-byte cap; ignoring",
                            service.usn(), MAX_DESCRIPTION_BYTES);
                    return;
                }
                DeviceDescription description = DeviceDescriptions.parse(bytes, service.location());
                services.computeIfPresent(service.usn(), (usn, current) -> current.withDescription(description));
            }
        } catch (IOException | IllegalArgumentException e) {
            log.debug("No description for {}: {}", service.usn(), e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static OptionalLong contentLength(HttpResponse<?> response) {
        try {
            return response.headers().firstValueAsLong("Content-Length");
        } catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
    }

    /** Reads at most {@code maxBytes} from {@code in}; {@code null} if the stream had more than that. */
    private static byte[] readAtMost(InputStream in, long maxBytes) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        long total = 0;
        int read;
        while ((read = in.read(chunk)) != -1) {
            total += read;
            if (total > maxBytes) {
                return null;
            }
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    private static Optional<URI> toUri(String value) {
        try {
            return Optional.of(URI.create(value));
        } catch (IllegalArgumentException e) {
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

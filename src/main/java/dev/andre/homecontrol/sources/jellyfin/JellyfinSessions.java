package dev.andre.homecontrol.sources.jellyfin;

import dev.andre.homecontrol.core.Device;
import tools.jackson.databind.JsonNode;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/** Finds the Jellyfin app open on a device and tells it to play (spec §4.2 route a, §5.3 rung 1). */
public class JellyfinSessions {

    private final JellyfinClient client;
    private final JellyfinSetupService setup;
    private final Function<String, Set<String>> addressesOfHost;

    public JellyfinSessions(JellyfinClient client, JellyfinSetupService setup) {
        this(client, setup, JellyfinSessions::resolve);
    }

    public JellyfinSessions(JellyfinClient client, JellyfinSetupService setup, Function<String, Set<String>> addressesOfHost) {
        this.client = client;
        this.setup = setup;
        this.addressesOfHost = addressesOfHost;
    }

    public List<JellyfinSession> controllable() {
        JellyfinConnection connection = connection();
        JsonNode sessions = client.get(connection, "/Sessions", Map.of("controllableByUserId", connection.userId()));
        List<JellyfinSession> result = new ArrayList<>();
        for (JsonNode node : sessions) {
            JellyfinSession session = new JellyfinSession(node.path("Id").asString(""), node.path("DeviceId").asString(""),
                    node.path("DeviceName").asString(""), node.path("Client").asString(""),
                    normalizeAddress(node.path("RemoteEndPoint").asString("")), instant(node.path("LastActivityDate").asString("")),
                    node.path("SupportsMediaControl").asBoolean(false));
            if (session.supportsMediaControl() && !session.id().isBlank() && !session.deviceId().equals(connection.deviceId())) {
                result.add(session);
            }
        }
        return result;
    }

    public Optional<JellyfinSession> sessionFor(Device device) {
        JellyfinSettings settings = setup.settings().orElseThrow(() ->
                new JellyfinException(JellyfinException.Kind.INVALID_INPUT, "Jellyfin is not connected"));
        return match(device.name(), addressesOfHost.apply(device.host()), controllable(), settings.sessionLinks().get(device.id()));
    }

    public void playNow(String sessionId, String itemId, long startPositionTicks) {
        String session = JellyfinClient.id(sessionId);
        String item = JellyfinClient.id(itemId);
        Map<String, String> query = new LinkedHashMap<>();
        query.put("playCommand", "PlayNow");
        query.put("itemIds", item);
        if (startPositionTicks > 0) {
            query.put("startPositionTicks", String.valueOf(startPositionTicks));
        }
        try {
            client.post(connection(), "/Sessions/" + session + "/Playing", query, null);
        } catch (JellyfinException e) {
            if (e.kind() == JellyfinException.Kind.NOT_FOUND) {
                throw new JellyfinException(JellyfinException.Kind.NOT_FOUND, "The Jellyfin app on that device has closed its session");
            }
            throw e;
        }
    }

    /**
     * First rule that applies wins: an explicit link (if present) is authoritative and never
     * overridden by guessing; then a unique address match, preferring a matching device name
     * among several and otherwise the most recent; then a uniquely-named session.
     */
    public static Optional<JellyfinSession> match(String deviceName, Set<String> deviceAddresses,
                                                  List<JellyfinSession> sessions, String linkedJellyfinDeviceId) {
        Comparator<JellyfinSession> recent = Comparator.comparing(JellyfinSession::lastActivity);
        if (linkedJellyfinDeviceId != null && !linkedJellyfinDeviceId.isBlank()) {
            return sessions.stream().filter(s -> s.deviceId().equals(linkedJellyfinDeviceId)).max(recent);
        }
        List<JellyfinSession> byAddress = sessions.stream()
                .filter(s -> !s.remoteAddress().isBlank() && deviceAddresses.contains(s.remoteAddress()))
                .toList();
        if (byAddress.size() == 1) {
            return Optional.of(byAddress.getFirst());
        }
        if (byAddress.size() > 1) {
            return byAddress.stream().filter(s -> s.deviceName().equalsIgnoreCase(deviceName)).max(recent)
                    .or(() -> byAddress.stream().max(recent));
        }
        List<JellyfinSession> byName = sessions.stream().filter(s -> s.deviceName().equalsIgnoreCase(deviceName)).toList();
        return byName.size() == 1 ? Optional.of(byName.getFirst()) : Optional.empty();
    }

    /**
     * Trim, lower-case; strips a bracketed IPv6 literal's port, a single trailing {@code :port}
     * on a plain address, an IPv4-mapped IPv6 prefix, and an IPv6 zone suffix.
     */
    public static String normalizeAddress(String raw) {
        String address = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
        if (address.startsWith("[")) {
            int end = address.indexOf(']');
            address = end < 0 ? address.substring(1) : address.substring(1, end);
        } else if (address.chars().filter(c -> c == ':').count() == 1) {
            address = address.substring(0, address.indexOf(':'));
        }
        if (address.startsWith("::ffff:") && address.substring(7).contains(".")) {
            address = address.substring(7);
        }
        int zone = address.indexOf('%');
        return zone < 0 ? address : address.substring(0, zone);
    }

    /** A device host resolves to itself plus every address it is known to answer at; an unknown host, to itself only. */
    public static Set<String> resolve(String host) {
        Set<String> addresses = new LinkedHashSet<>();
        addresses.add(normalizeAddress(host));
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                addresses.add(normalizeAddress(address.getHostAddress()));
            }
        } catch (UnknownHostException | SecurityException ignored) {
            // the literal host is all we can compare
        }
        return addresses;
    }

    private JellyfinConnection connection() {
        return setup.connection().orElseThrow(() ->
                new JellyfinException(JellyfinException.Kind.INVALID_INPUT, "Jellyfin is not connected"));
    }

    private static Instant instant(String value) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            return Instant.EPOCH;
        }
    }
}

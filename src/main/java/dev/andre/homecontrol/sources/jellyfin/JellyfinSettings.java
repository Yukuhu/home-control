package dev.andre.homecontrol.sources.jellyfin;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/** Non-secret Jellyfin settings kept in sources.json, as this record's JSON. The token lives in the secret store. */
public record JellyfinSettings(URI serverUrl, URI deviceServerUrl, String serverId, String serverName,
                               String serverVersion, String userId, String userName, AuthMode authMode,
                               String deviceId, String castReceiverId, Map<String, String> sessionLinks, Map<String, Player> players) {

    public static final String SOURCE_ID = "jellyfin";
    private static final String SERVER_URL_KEY = "serverUrl";
    private static final String USER_ID_KEY = "userId";
    public static final String TOKEN_SECRET = "jellyfin.token";
    public static final String DEFAULT_CAST_RECEIVER_ID = "F007D354";
    private static final String LINK_PREFIX = "link.";
    private static final Pattern IP_V4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");

    public enum Player { JELLYFIN, VLC }

    public JellyfinSettings(URI serverUrl, URI deviceServerUrl, String serverId, String serverName,
                            String serverVersion, String userId, String userName, AuthMode authMode,
                            String deviceId, String castReceiverId, Map<String, String> sessionLinks) {
        this(serverUrl, deviceServerUrl, serverId, serverName, serverVersion, userId, userName,
                authMode, deviceId, castReceiverId, sessionLinks, Map.of());
    }

    public Player player(String deviceId) {
        return players.getOrDefault(deviceId, Player.JELLYFIN);
    }

    public JellyfinSettings withPlayer(String deviceId, Player player) {
        Map<String, Player> updated = new LinkedHashMap<>(players);
        if (player == Player.JELLYFIN) updated.remove(deviceId);
        else updated.put(deviceId, Objects.requireNonNull(player));
        return new JellyfinSettings(serverUrl, deviceServerUrl, serverId, serverName, serverVersion,
                userId, userName, authMode, this.deviceId, castReceiverId, sessionLinks, updated);
    }

    public enum AuthMode { PASSWORD, API_KEY }

    public JellyfinSettings {
        players = players == null ? Map.of() : Map.copyOf(players);
        sessionLinks = sessionLinks == null ? Map.of() : Map.copyOf(sessionLinks);
    }

    /**
     * sources.json version 1's flat section, with {@code link.<device>} and {@code player.<device>} keys. Empty when
     * it lacks the server address or the user.
     */
    public static Optional<JellyfinSettings> fromVersionOne(Map<String, String> map) {
        if (map == null || map.get(SERVER_URL_KEY) == null || map.get(USER_ID_KEY) == null) {
            return Optional.empty();
        }
        Map<String, String> links = new LinkedHashMap<>();
        Map<String, Player> players = new LinkedHashMap<>();
        map.forEach((key, value) -> {
            if (key.startsWith("player.") && "vlc".equals(value)) players.put(key.substring(7), Player.VLC);
            if (key.startsWith(LINK_PREFIX)) {
                links.put(key.substring(LINK_PREFIX.length()), value);
            }
        });
        return Optional.of(new JellyfinSettings(URI.create(map.get(SERVER_URL_KEY)),
                URI.create(map.getOrDefault("deviceServerUrl", map.get(SERVER_URL_KEY))),
                map.get("serverId"), map.get("serverName"), map.get("serverVersion"), map.get(USER_ID_KEY),
                map.get("userName"), AuthMode.valueOf(map.getOrDefault("authMode", AuthMode.PASSWORD.name())),
                map.get("deviceId"), map.getOrDefault("castReceiverId", DEFAULT_CAST_RECEIVER_ID), links, players));
    }

    /** A blank {@code jellyfinDeviceId} removes the link. */
    public JellyfinSettings withSessionLink(String deviceId, String jellyfinDeviceId) {
        Map<String, String> links = new LinkedHashMap<>(sessionLinks);
        if (jellyfinDeviceId == null || jellyfinDeviceId.isBlank()) {
            links.remove(deviceId);
        } else {
            links.put(deviceId, jellyfinDeviceId);
        }
        return new JellyfinSettings(serverUrl, deviceServerUrl, serverId, serverName, serverVersion, userId, userName,
                authMode, this.deviceId, castReceiverId, links, players);
    }

    /** True when a TV or speaker is unlikely to resolve or reach this address (loopback or a Docker service name). */
    public boolean deviceAddressLooksLocal() {
        String host = deviceServerUrl.getHost();
        if (host == null) {
            return true;
        }
        String bare = host.startsWith("[") ? host.substring(1, host.length() - 1) : host;
        return bare.equalsIgnoreCase("localhost") || bare.startsWith("127.") || bare.equals("::1")
                || (!bare.contains(".") && !bare.contains(":") && !IP_V4.matcher(bare).matches());
    }
}

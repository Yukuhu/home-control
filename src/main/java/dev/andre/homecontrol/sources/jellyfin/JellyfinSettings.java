package dev.andre.homecontrol.sources.jellyfin;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Non-secret Jellyfin settings kept in sources.json. The token lives in the secret store. */
public record JellyfinSettings(URI serverUrl, URI deviceServerUrl, String serverId, String serverName,
                               String serverVersion, String userId, String userName, AuthMode authMode,
                               String deviceId, String castReceiverId, Map<String, String> sessionLinks) {

    public static final String SOURCE_ID = "jellyfin";
    public static final String TOKEN_SECRET = "jellyfin.token";
    public static final String DEFAULT_CAST_RECEIVER_ID = "F007D354";
    private static final String LINK_PREFIX = "link.";
    private static final Pattern IP_V4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");

    public enum AuthMode { PASSWORD, API_KEY }

    public JellyfinSettings {
        sessionLinks = sessionLinks == null ? Map.of() : Map.copyOf(sessionLinks);
    }

    public Map<String, String> toMap() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("serverUrl", serverUrl.toString());
        map.put("deviceServerUrl", deviceServerUrl.toString());
        map.put("serverId", serverId);
        map.put("serverName", serverName);
        map.put("serverVersion", serverVersion);
        map.put("userId", userId);
        map.put("userName", userName);
        map.put("authMode", authMode.name());
        map.put("deviceId", deviceId);
        map.put("castReceiverId", castReceiverId);
        new TreeMap<>(sessionLinks).forEach((device, jellyfinDevice) -> map.put(LINK_PREFIX + device, jellyfinDevice));
        return map;
    }

    public static Optional<JellyfinSettings> from(Map<String, String> map) {
        if (map == null || map.get("serverUrl") == null || map.get("userId") == null) {
            return Optional.empty();
        }
        Map<String, String> links = new LinkedHashMap<>();
        map.forEach((key, value) -> {
            if (key.startsWith(LINK_PREFIX)) {
                links.put(key.substring(LINK_PREFIX.length()), value);
            }
        });
        return Optional.of(new JellyfinSettings(URI.create(map.get("serverUrl")),
                URI.create(map.getOrDefault("deviceServerUrl", map.get("serverUrl"))),
                map.get("serverId"), map.get("serverName"), map.get("serverVersion"), map.get("userId"),
                map.get("userName"), AuthMode.valueOf(map.getOrDefault("authMode", AuthMode.PASSWORD.name())),
                map.get("deviceId"), map.getOrDefault("castReceiverId", DEFAULT_CAST_RECEIVER_ID), links));
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
                authMode, this.deviceId, castReceiverId, links);
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

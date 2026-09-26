package dev.andre.homecontrol.sources.youtube;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** Non-secret YouTube settings kept in sources.json. The OAuth client and tokens live in the secret store. */
public record YouTubeSettings(Instant connectedAt, String channelId, String channelTitle, boolean watchLater,
                              Map<String, String> playlists, Set<String> loungeDevices, String loungeRemoteId) {

    public static final String SOURCE_ID = "youtube";
    public static final String CLIENT_ID = "youtube.client-id";
    public static final String CLIENT_SECRET = "youtube.client-secret";
    public static final String REFRESH_TOKEN = "youtube.refresh-token";

    private static final String PLAYLIST_PREFIX = "playlist.";
    private static final Pattern PLAYLIST_ID = Pattern.compile("[A-Za-z0-9_-]{2,64}");
    private static final String LOUNGE_DEVICES_KEY = "lounge.devices";
    private static final String LOUNGE_REMOTE_ID_KEY = "lounge.remoteId";

    public static final YouTubeSettings EMPTY = from(Map.of());

    public YouTubeSettings {
        Map<String, String> source = playlists == null ? Map.of() : playlists;
        Map<String, String> sorted = new TreeMap<>(
                Comparator.comparing((String id) -> source.getOrDefault(id, ""), String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(Comparator.naturalOrder()));
        sorted.putAll(source);
        // Map.copyOf does not preserve iteration order; a LinkedHashMap wrapped as unmodifiable does.
        playlists = Collections.unmodifiableMap(new LinkedHashMap<>(sorted));
        // Set.copyOf does not preserve iteration order either; wrap the sorted TreeSet directly instead.
        loungeDevices = Collections.unmodifiableSortedSet(new TreeSet<>(loungeDevices == null ? Set.of() : loungeDevices));
    }

    public static YouTubeSettings from(Map<String, String> map) {
        if (map == null) {
            map = Map.of();
        }
        Instant connectedAt = parseInstant(map.get("connectedAt"));
        boolean watchLater = "true".equals(map.get("watchLater"));
        Map<String, String> playlists = new LinkedHashMap<>();
        map.forEach((key, value) -> {
            if (key.startsWith(PLAYLIST_PREFIX)) {
                String id = key.substring(PLAYLIST_PREFIX.length());
                if (PLAYLIST_ID.matcher(id).matches()) {
                    playlists.put(id, value);
                }
            }
        });
        Set<String> loungeDevices = new TreeSet<>();
        String devices = map.get(LOUNGE_DEVICES_KEY);
        if (devices != null) {
            for (String device : devices.split(",")) {
                if (!device.isBlank()) {
                    loungeDevices.add(device);
                }
            }
        }
        return new YouTubeSettings(connectedAt, map.get("channelId"), map.get("channelTitle"), watchLater,
                playlists, loungeDevices, map.get(LOUNGE_REMOTE_ID_KEY));
    }

    public Map<String, String> toMap() {
        Map<String, String> map = new LinkedHashMap<>();
        putIfPresent(map, "connectedAt", connectedAt == null ? null : connectedAt.toString());
        putIfPresent(map, "channelId", channelId);
        putIfPresent(map, "channelTitle", channelTitle);
        if (watchLater) {
            map.put("watchLater", "true");
        }
        playlists.forEach((id, title) -> putIfPresent(map, PLAYLIST_PREFIX + id, title));
        if (!loungeDevices.isEmpty()) {
            map.put(LOUNGE_DEVICES_KEY, String.join(",", new TreeSet<>(loungeDevices)));
        }
        putIfPresent(map, LOUNGE_REMOTE_ID_KEY, loungeRemoteId);
        return map;
    }

    public YouTubeSettings withConnection(Instant connectedAt, String channelId, String channelTitle) {
        return new YouTubeSettings(connectedAt, channelId, channelTitle, watchLater, playlists, loungeDevices, loungeRemoteId);
    }

    /** Disconnecting the Google account clears the account and library; the Lounge pairing survives. */
    public YouTubeSettings withoutAccount() {
        return new YouTubeSettings(null, null, null, false, Map.of(), loungeDevices, loungeRemoteId);
    }

    public YouTubeSettings withWatchLater(boolean watchLater) {
        return new YouTubeSettings(connectedAt, channelId, channelTitle, watchLater, playlists, loungeDevices, loungeRemoteId);
    }

    public YouTubeSettings withPlaylists(Map<String, String> playlists) {
        return new YouTubeSettings(connectedAt, channelId, channelTitle, watchLater, playlists, loungeDevices, loungeRemoteId);
    }

    public YouTubeSettings withLoungeDevice(String deviceId, boolean enabled) {
        Set<String> devices = new TreeSet<>(loungeDevices);
        if (enabled) {
            devices.add(deviceId);
        } else {
            devices.remove(deviceId);
        }
        return new YouTubeSettings(connectedAt, channelId, channelTitle, watchLater, playlists, devices, loungeRemoteId);
    }

    public YouTubeSettings withLoungeRemoteId(String loungeRemoteId) {
        return new YouTubeSettings(connectedAt, channelId, channelTitle, watchLater, playlists, loungeDevices, loungeRemoteId);
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException _) {
            return null;
        }
    }

    private static void putIfPresent(Map<String, String> map, String key, String value) {
        if (value != null && !value.isBlank()) {
            map.put(key, value);
        }
    }
}

package dev.andre.homecontrol.sources.youtube;

import dev.andre.homecontrol.core.content.ContentSourceException;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The user's own playlists and the Watch Later list, as far as the API still exposes it. */
public class YouTubePlaylists {

    public static final String WATCH_LATER_ID = "WL";
    public static final String WATCH_LATER_UNAVAILABLE = "YouTube does not share Watch Later with other apps for most"
            + " accounts (an API change in 2016). Save videos to one of your own playlists and show that playlist here instead.";
    private static final int MAX_PAGES = 10;

    public record PlaylistSummary(String id, String title, int itemCount) {
    }

    private record Memo(List<YouTubeVideo> videos, Instant at) {
    }

    private final YouTubeApiClient api;
    private final YouTubeProperties properties;
    private final Clock clock;
    private final Map<String, Memo> memos = new HashMap<>();
    private Map<String, PlaylistSummary> loaded = Map.of();
    private Instant watchLaterUnavailableAt;

    public YouTubePlaylists(YouTubeApiClient api, YouTubeProperties properties, Clock clock) {
        this.api = api;
        this.properties = properties;
        this.clock = clock;
    }

    public synchronized List<PlaylistSummary> mine() {
        List<PlaylistSummary> found = new ArrayList<>();
        String pageToken = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            Map<String, String> query = new LinkedHashMap<>();
            query.put("part", "snippet,contentDetails");
            query.put("mine", "true");
            query.put("maxResults", "50");
            query.put("pageToken", pageToken);
            JsonNode response = api.get(QuotaLedger.Call.PLAYLISTS_LIST, "playlists", query);
            for (JsonNode item : response.path("items")) {
                String id = item.path("id").asString("");
                if (id.matches("[A-Za-z0-9_-]{2,64}")) {
                    found.add(new PlaylistSummary(id, item.path("snippet").path("title").asString(id),
                            item.path("contentDetails").path("itemCount").asInt(0)));
                }
            }
            pageToken = response.path("nextPageToken").asString("");
            if (pageToken.isBlank()) {
                break;
            }
        }
        found.sort(Comparator.comparing(PlaylistSummary::title, String.CASE_INSENSITIVE_ORDER).thenComparing(PlaylistSummary::id));
        Map<String, PlaylistSummary> byId = new LinkedHashMap<>();
        found.forEach(p -> byId.put(p.id(), p));
        loaded = byId;
        return List.copyOf(found);
    }

    public synchronized Optional<PlaylistSummary> loaded(String playlistId) {
        return Optional.ofNullable(loaded.get(playlistId));
    }

    public synchronized List<PlaylistSummary> loadedList() {
        return List.copyOf(loaded.values());
    }

    public synchronized List<YouTubeVideo> items(String playlistId) {
        Instant now = clock.instant();
        Memo memo = memos.get(playlistId);
        if (memo != null && now.isBefore(memo.at().plus(properties.minRefreshSpacing()))) {
            return memo.videos();
        }
        Map<String, String> query = new LinkedHashMap<>();
        query.put("part", "snippet,contentDetails");
        query.put("playlistId", playlistId);
        query.put("maxResults", String.valueOf(Math.min(properties.railSize(), 50)));
        List<YouTubeVideo> videos = YouTubeVideoMapper.playlistItems(
                api.get(QuotaLedger.Call.PLAYLIST_ITEMS_LIST, "playlistItems", query));
        memos.put(playlistId, new Memo(videos, now));
        return videos;
    }

    public List<YouTubeVideo> watchLater() {
        Instant now = clock.instant();
        synchronized (this) {
            if (watchLaterUnavailableAt != null && now.isBefore(watchLaterUnavailableAt.plus(properties.minRefreshSpacing()))) {
                // Memoized: an empty or failed Watch Later result is honest and unlikely to change
                // within the spacing window, so don't spend another call finding that out again.
                throw new ContentSourceException(WATCH_LATER_UNAVAILABLE);
            }
        }
        List<YouTubeVideo> videos;
        try {
            videos = items(WATCH_LATER_ID);
        } catch (YouTubeException e) {
            if (e.kind() == YouTubeException.Kind.NOT_FOUND) {
                return rethrowWatchLaterUnavailable(now);
            }
            throw e;
        }
        if (videos.isEmpty()) {
            synchronized (this) {
                memos.remove(WATCH_LATER_ID);
            }
            return rethrowWatchLaterUnavailable(now);
        }
        synchronized (this) {
            watchLaterUnavailableAt = null;
        }
        return videos;
    }

    private synchronized List<YouTubeVideo> rethrowWatchLaterUnavailable(Instant now) {
        watchLaterUnavailableAt = now;
        throw new ContentSourceException(WATCH_LATER_UNAVAILABLE);
    }

    public synchronized void clear() {
        memos.clear();
        loaded = Map.of();
        watchLaterUnavailableAt = null;
    }

    public static String railId(String playlistId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(playlistId.getBytes(StandardCharsets.UTF_8));
            return "pl-" + HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}

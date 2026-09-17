package dev.andre.homecontrol.sources.youtube;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** "New from your subscriptions": subscriptions → uploads playlists → newest videos, inside a per-refresh budget. */
public class SubscriptionsFeed {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionsFeed.class);
    private static final Set<YouTubeException.Kind> FATAL = Set.of(YouTubeException.Kind.REVOKED,
            YouTubeException.Kind.UNAUTHORIZED, YouTubeException.Kind.FORBIDDEN, YouTubeException.Kind.NOT_CONFIGURED);

    private record Polled(List<YouTubeVideo> videos, Instant at) {
    }

    private final YouTubeApiClient api;
    private final YouTubeProperties properties;
    private final Clock clock;

    private LinkedHashMap<String, String> subscriptions;
    private Instant subscriptionsFetchedAt;
    private final Map<String, String> uploads = new HashMap<>();
    private final Map<String, Polled> polled = new HashMap<>();
    private List<YouTubeVideo> lastResult;
    private Instant lastRefreshAt;
    private YouTubeException firstRefreshFailure;
    private Instant firstRefreshFailedAt;

    public SubscriptionsFeed(YouTubeApiClient api, YouTubeProperties properties, Clock clock) {
        this.api = api;
        this.properties = properties;
        this.clock = clock;
    }

    public synchronized List<YouTubeVideo> refresh() {
        Instant now = clock.instant();
        if (lastResult != null && now.isBefore(lastRefreshAt.plus(properties.minRefreshSpacing()))) {
            return lastResult;
        }
        // Before ever succeeding once, a failure is memoized with a timestamp too — otherwise
        // every scheduled refresh tick would hammer a source that is, say, not yet configured.
        if (lastResult == null && firstRefreshFailure != null
                && now.isBefore(firstRefreshFailedAt.plus(properties.minRefreshSpacing()))) {
            throw firstRefreshFailure;
        }
        try {
            if (subscriptions == null || !now.isBefore(subscriptionsFetchedAt.plus(properties.subscriptionsRefresh()))) {
                loadSubscriptions(now);
            }
            pollChannels(now);
        } catch (YouTubeException e) {
            if (e.kind() != YouTubeException.Kind.QUOTA_EXHAUSTED || polled.isEmpty()) {
                firstRefreshFailure = e;
                firstRefreshFailedAt = now;
                throw e;
            }
        }
        firstRefreshFailure = null;
        firstRefreshFailedAt = null;
        lastResult = merge();
        lastRefreshAt = now;
        return lastResult;
    }

    public synchronized void clear() {
        subscriptions = null;
        subscriptionsFetchedAt = null;
        uploads.clear();
        polled.clear();
        lastResult = null;
        lastRefreshAt = null;
        firstRefreshFailure = null;
        firstRefreshFailedAt = null;
    }

    private void loadSubscriptions(Instant now) {
        LinkedHashMap<String, String> fresh = new LinkedHashMap<>();
        String pageToken = null;
        for (int page = 0; page < properties.maxSubscriptionPages(); page++) {
            Map<String, String> query = new LinkedHashMap<>();
            query.put("part", "snippet");
            query.put("mine", "true");
            query.put("maxResults", "50");
            query.put("pageToken", pageToken);
            JsonNode response = api.get(QuotaLedger.Call.SUBSCRIPTIONS_LIST, "subscriptions", query);
            for (JsonNode item : response.path("items")) {
                String channelId = item.path("snippet").path("resourceId").path("channelId").asString("");
                if (!channelId.isBlank()) {
                    fresh.put(channelId, item.path("snippet").path("title").asString(""));
                }
            }
            pageToken = response.path("nextPageToken").asString("");
            if (pageToken.isBlank()) {
                break;
            }
        }
        subscriptions = fresh;
        subscriptionsFetchedAt = now;
        uploads.keySet().retainAll(fresh.keySet());
        polled.keySet().retainAll(fresh.keySet());
        List<String> unknown = fresh.keySet().stream().filter(id -> !uploads.containsKey(id)).toList();
        for (int from = 0; from < unknown.size(); from += 50) {
            List<String> batch = unknown.subList(from, Math.min(unknown.size(), from + 50));
            Map<String, String> query = new LinkedHashMap<>();
            query.put("part", "contentDetails");
            query.put("id", String.join(",", batch));
            query.put("maxResults", "50");
            JsonNode response = api.get(QuotaLedger.Call.CHANNELS_LIST, "channels", query);
            for (String id : batch) {
                uploads.put(id, "");
            }
            for (JsonNode channel : response.path("items")) {
                String id = channel.path("id").asString("");
                if (batch.contains(id)) {
                    uploads.put(id, channel.path("contentDetails").path("relatedPlaylists").path("uploads").asString(""));
                }
            }
        }
    }

    private void pollChannels(Instant now) {
        List<String> order = new ArrayList<>(subscriptions.keySet());
        List<String> candidates = order.stream()
                .filter(id -> !uploads.getOrDefault(id, "").isBlank())
                .sorted(Comparator.comparing((String id) -> polled.containsKey(id) ? polled.get(id).at() : Instant.MIN)
                        .thenComparing(order::indexOf))
                .limit(properties.channelsPerRefresh())
                .toList();
        for (String channelId : candidates) {
            Map<String, String> query = new LinkedHashMap<>();
            query.put("part", "snippet,contentDetails");
            query.put("playlistId", uploads.get(channelId));
            query.put("maxResults", String.valueOf(properties.videosPerChannel()));
            try {
                JsonNode response = api.get(QuotaLedger.Call.PLAYLIST_ITEMS_LIST, "playlistItems", query);
                polled.put(channelId, new Polled(YouTubeVideoMapper.playlistItems(response), now));
            } catch (YouTubeException e) {
                if (e.kind() == YouTubeException.Kind.NOT_FOUND) {
                    polled.put(channelId, new Polled(List.of(), now));
                } else if (e.kind() == YouTubeException.Kind.QUOTA_EXHAUSTED || FATAL.contains(e.kind())) {
                    throw e;
                } else {
                    log.debug("Skipping channel {} this time: {}", channelId, e.getMessage());
                }
            }
        }
    }

    private List<YouTubeVideo> merge() {
        Map<String, YouTubeVideo> unique = new LinkedHashMap<>();
        polled.values().forEach(p -> p.videos().forEach(v -> unique.putIfAbsent(v.id(), v)));
        return unique.values().stream()
                .sorted(Comparator.comparing(YouTubeVideo::publishedAt).reversed().thenComparing(YouTubeVideo::id))
                .limit(properties.railSize())
                .toList();
    }
}

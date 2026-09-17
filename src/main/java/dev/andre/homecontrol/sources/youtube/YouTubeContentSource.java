package dev.andre.homecontrol.sources.youtube;

import dev.andre.homecontrol.core.content.ContentSource;
import dev.andre.homecontrol.core.content.Rail;
import dev.andre.homecontrol.core.content.RailDescriptor;
import dev.andre.homecontrol.core.playback.ContentItem;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** "New from your subscriptions", read from the cached feed; only unknown item lookups cost an API call. */
public class YouTubeContentSource implements ContentSource {

    static final String SUBSCRIPTIONS = "subscriptions";
    private static final RailDescriptor SUBSCRIPTIONS_RAIL =
            new RailDescriptor(YouTubeVideo.SOURCE_ID, SUBSCRIPTIONS, "New from your subscriptions");

    private final YouTubeSetupService setup;
    private final SubscriptionsFeed feed;
    private final YouTubeApiClient api;
    private final KnownVideos known;
    private final YouTubeProperties properties;
    private final Clock clock;

    public YouTubeContentSource(YouTubeSetupService setup, SubscriptionsFeed feed, YouTubeApiClient api,
                                KnownVideos known, YouTubeProperties properties, Clock clock) {
        this.setup = setup;
        this.feed = feed;
        this.api = api;
        this.known = known;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public String id() {
        return YouTubeVideo.SOURCE_ID;
    }

    @Override
    public String displayName() {
        return "YouTube";
    }

    @Override
    public boolean available() {
        return setup.connected();
    }

    @Override
    public List<RailDescriptor> rails() {
        return available() ? List.of(SUBSCRIPTIONS_RAIL) : List.of();
    }

    @Override
    public Rail rail(String railId) {
        if (!SUBSCRIPTIONS.equals(railId)) {
            throw new IllegalArgumentException("YouTube has no rail " + railId);
        }
        List<YouTubeVideo> videos = feed.refresh();
        known.remember(videos);
        return new Rail(SUBSCRIPTIONS_RAIL, videos.stream().map(YouTubeVideo::toItem).toList(), clock.instant());
    }

    @Override
    public Optional<ContentItem> item(String itemId) {
        if (!YouTubeVideo.validId(itemId)) {
            return Optional.empty();
        }
        Optional<YouTubeVideo> cached = known.find(itemId);
        if (cached.isPresent()) {
            return cached.map(YouTubeVideo::toItem);
        }
        Map<String, String> query = new LinkedHashMap<>();
        query.put("part", "snippet");
        query.put("id", itemId);
        try {
            JsonNode response = api.get(QuotaLedger.Call.VIDEOS_LIST, "videos", query);
            Optional<YouTubeVideo> video = YouTubeVideoMapper.fromVideo(response.path("items").path(0));
            video.ifPresent(v -> known.remember(List.of(v)));
            return video.map(YouTubeVideo::toItem);
        } catch (YouTubeException e) {
            if (e.kind() == YouTubeException.Kind.NOT_FOUND) {
                return Optional.empty();
            }
            throw e;
        }
    }

    @Override
    public Duration defaultRefreshInterval() {
        return properties.refreshInterval();
    }

    /** Called when the Google account is disconnected: nothing about the old account should linger. */
    public void forgetAccount() {
        feed.clear();
    }
}

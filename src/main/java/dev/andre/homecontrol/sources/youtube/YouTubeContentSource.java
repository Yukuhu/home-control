package dev.andre.homecontrol.sources.youtube;

import dev.andre.homecontrol.core.content.ContentSource;
import dev.andre.homecontrol.core.content.ContentSourceException;
import dev.andre.homecontrol.core.content.Rail;
import dev.andre.homecontrol.core.content.RailDescriptor;
import dev.andre.homecontrol.core.playback.ContentItem;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** "New from your subscriptions", Watch Later and chosen playlists; only unknown item lookups cost an API call. */
public class YouTubeContentSource implements ContentSource {

    static final String SUBSCRIPTIONS = "subscriptions";
    static final String WATCH_LATER = "watch-later";
    private static final RailDescriptor SUBSCRIPTIONS_RAIL =
            new RailDescriptor(YouTubeVideo.SOURCE_ID, SUBSCRIPTIONS, "New from your subscriptions");
    private static final RailDescriptor WATCH_LATER_RAIL =
            new RailDescriptor(YouTubeVideo.SOURCE_ID, WATCH_LATER, "Watch Later");

    private final YouTubeSetupService setup;
    private final SubscriptionsFeed feed;
    private final YouTubeApiClient api;
    private final YouTubePlaylists playlists;
    private final YouTubeSearch search;
    private final QuotaLedger ledger;
    private final KnownVideos known;
    private final YouTubeProperties properties;
    private final Clock clock;

    public YouTubeContentSource(YouTubeSetupService setup, SubscriptionsFeed feed, YouTubeApiClient api,
                                YouTubePlaylists playlists, YouTubeSearch search, QuotaLedger ledger, KnownVideos known,
                                YouTubeProperties properties, Clock clock) {
        this.setup = setup;
        this.feed = feed;
        this.api = api;
        this.playlists = playlists;
        this.search = search;
        this.ledger = ledger;
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
        if (!available()) {
            return List.of();
        }
        List<RailDescriptor> rails = new ArrayList<>();
        rails.add(SUBSCRIPTIONS_RAIL);
        YouTubeSettings settings = setup.settings();
        if (settings.watchLater()) {
            rails.add(WATCH_LATER_RAIL);
        }
        settings.playlists().forEach((id, title) ->
                rails.add(new RailDescriptor(YouTubeVideo.SOURCE_ID, YouTubePlaylists.railId(id), title)));
        return List.copyOf(rails);
    }

    @Override
    public Rail rail(String railId) {
        if (SUBSCRIPTIONS.equals(railId)) {
            List<YouTubeVideo> videos = feed.refresh();
            known.remember(videos);
            return new Rail(SUBSCRIPTIONS_RAIL, videos.stream().map(YouTubeVideo::toItem).toList(), clock.instant());
        }
        if (WATCH_LATER.equals(railId)) {
            if (!setup.settings().watchLater()) {
                throw new IllegalArgumentException("YouTube has no rail " + railId);
            }
            List<YouTubeVideo> videos = playlists.watchLater();
            known.remember(videos);
            return new Rail(WATCH_LATER_RAIL, videos.stream().map(YouTubeVideo::toItem).toList(), clock.instant());
        }
        YouTubeSettings settings = setup.settings();
        Optional<Map.Entry<String, String>> selected = settings.playlists().entrySet().stream()
                .filter(entry -> YouTubePlaylists.railId(entry.getKey()).equals(railId))
                .findFirst();
        if (selected.isEmpty()) {
            throw new IllegalArgumentException("YouTube has no rail " + railId);
        }
        String playlistId = selected.get().getKey();
        String title = selected.get().getValue();
        RailDescriptor descriptor = new RailDescriptor(YouTubeVideo.SOURCE_ID, railId, title);
        List<YouTubeVideo> videos;
        try {
            videos = playlists.items(playlistId);
        } catch (YouTubeException e) {
            if (e.kind() == YouTubeException.Kind.NOT_FOUND) {
                throw new ContentSourceException("The playlist “" + title
                        + "” no longer exists or is private to another account; choose it again on the setup page");
            }
            throw e;
        }
        known.remember(videos);
        return new Rail(descriptor, videos.stream().map(YouTubeVideo::toItem).toList(), clock.instant());
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

    @Override
    public boolean searchable() {
        return true;
    }

    @Override
    public boolean searchOnDemand() {
        return true;
    }

    @Override
    public Optional<String> searchNote() {
        QuotaLedger.Usage usage = ledger.usage();
        int left = usage.searchesLeft();
        if (left <= 0) {
            String until = DateTimeFormatter.ofPattern("HH:mm").format(usage.resetsAt());
            return Optional.of("YouTube searches used up until " + until);
        }
        return Optional.of(left + " of " + usage.searchesPerDay() + " YouTube searches left today");
    }

    @Override
    public List<ContentItem> search(String query, int limit) {
        return search.search(query, limit).stream().map(YouTubeVideo::toItem).toList();
    }

    /** Called when the Google account is disconnected: nothing about the old account should linger. */
    public void forgetAccount() {
        feed.clear();
        playlists.clear();
    }
}

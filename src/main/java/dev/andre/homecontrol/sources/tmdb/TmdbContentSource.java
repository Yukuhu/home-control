package dev.andre.homecontrol.sources.tmdb;

import dev.andre.homecontrol.core.content.ContentSource;
import dev.andre.homecontrol.core.content.ContentSourceException;
import dev.andre.homecontrol.core.content.PinnedLinks;
import dev.andre.homecontrol.core.content.Rail;
import dev.andre.homecontrol.core.content.RailDescriptor;
import dev.andre.homecontrol.core.content.SourcePreferences;
import dev.andre.homecontrol.core.content.StreamingProviders;
import dev.andre.homecontrol.core.playback.ContentItem;
import dev.andre.homecontrol.core.playback.PlayableRef;
import dev.andre.homecontrol.core.playback.ServiceLinks;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/** TMDB: titles, artwork, where they stream, and what is trending on the household's own services. */
public class TmdbContentSource implements ContentSource {

    private static final String NOT_CONNECTED = "TMDB is not connected";
    private static final String LANGUAGE = "language";

    public static final String SOURCE_ID = "tmdb";
    private static final RailDescriptor TRENDING = new RailDescriptor(SOURCE_ID, "trending", "Trending on your services");
    /** Failures that are not about one title: TMDB is away, limiting this server, or refusing its key. */
    private static final Set<ContentSourceException.Kind> STOPS_THE_LOOKUPS = EnumSet.of(
            ContentSourceException.Kind.UNREACHABLE, ContentSourceException.Kind.RATE_LIMITED,
            ContentSourceException.Kind.UNAUTHORIZED);

    private final TmdbSetupService setup;
    private final TmdbClient client;
    private final TmdbImages images;
    private final TmdbWatchProviders providers;
    private final TmdbProperties properties;
    private final Supplier<SourcePreferences> preferences;
    private final ProviderMatcher matcher;
    private final ObjectProvider<PinnedLinks> pinnedLinks;
    private final Clock clock;

    // Spring constructor injection: eight distinct collaborators, no cohesive subset worth its own type.
    @SuppressWarnings("java:S107")
    public TmdbContentSource(TmdbSetupService setup, TmdbClient client, TmdbImages images,
                             TmdbWatchProviders providers, TmdbProperties properties,
                             Supplier<SourcePreferences> preferences, ProviderMatcher matcher,
                             ObjectProvider<PinnedLinks> pinnedLinks) {
        this(setup, client, images, providers, properties, preferences, matcher, pinnedLinks, Clock.systemUTC());
    }

    /** Package-private: lets tests pin {@code fetchedAt}. */
    // The injected collaborators plus the clock tests pin.
    @SuppressWarnings("java:S107")
    TmdbContentSource(TmdbSetupService setup, TmdbClient client, TmdbImages images,
                      TmdbWatchProviders providers, TmdbProperties properties,
                      Supplier<SourcePreferences> preferences, ProviderMatcher matcher,
                      ObjectProvider<PinnedLinks> pinnedLinks, Clock clock) {
        this.setup = setup;
        this.client = client;
        this.images = images;
        this.providers = providers;
        this.properties = properties;
        this.preferences = preferences;
        this.matcher = matcher;
        this.pinnedLinks = pinnedLinks;
        this.clock = clock;
    }

    @Override
    public String id() {
        return SOURCE_ID;
    }

    @Override
    public String displayName() {
        return "TMDB";
    }

    @Override
    public boolean available() {
        return setup.credential().isPresent();
    }

    @Override
    public List<RailDescriptor> rails() {
        return available() ? List.of(TRENDING) : List.of();
    }

    @Override
    public Rail rail(String railId) {
        if (!TRENDING.id().equals(railId)) {
            throw new IllegalArgumentException("TMDB has no rail '" + railId + "'");
        }
        TmdbCredential credential = setup.credential()
                .orElseThrow(() -> new ContentSourceException(ContentSourceException.Kind.NOT_CONFIGURED, NOT_CONNECTED));
        SourcePreferences prefs = preferences.get();
        List<String> configuredProviders = prefs.providers();
        if (configuredProviders.isEmpty()) {
            throw new ContentSourceException(ContentSourceException.Kind.NOT_CONFIGURED, "Choose your streaming services in Setup to see what is trending on them");
        }
        List<JsonNode> candidates = collectTrendingCandidates(credential, prefs.locale());
        List<ContentItem> items = new ArrayList<>();
        Lookups lookups = new Lookups(credential, prefs.region());
        for (int i = 0; i < candidates.size() && items.size() < properties.railSize() && !lookups.stopped; i++) {
            JsonNode candidate = candidates.get(i);
            TmdbMediaRef ref = TmdbMediaRef.of(candidate, null).orElseThrow();
            lookups.providers(ref).ifPresent(watchProviders ->
                    trendingItem(credential, candidate, ref, matcher.matches(watchProviders, configuredProviders))
                            .ifPresent(items::add));
        }
        // Only a failure of every lookup fails the rail; a failure means at least one lookup was attempted.
        if (!lookups.anySucceeded && lookups.firstFailure != null) {
            throw lookups.firstFailure;
        }
        return new Rail(TRENDING, items, clock.instant());
    }

    /** One rail's lookups of where its candidates stream, and what they came to. */
    private final class Lookups {
        private final TmdbCredential credential;
        private final String region;
        private TmdbException firstFailure;
        private boolean anySucceeded;
        private boolean stopped;

        Lookups(TmdbCredential credential, String region) {
            this.credential = credential;
            this.region = region;
        }

        /** Empty when the lookup failed; a failure that is not about this title stops the lookups. */
        Optional<List<WatchProvider>> providers(TmdbMediaRef ref) {
            try {
                List<WatchProvider> found = TmdbContentSource.this.providers.providers(credential, ref, region);
                anySucceeded = true;
                return Optional.of(found);
            } catch (TmdbException e) {
                firstFailure = firstFailure == null ? e : firstFailure;
                // Every further lookup would fail the same way, and each waits out its timeout.
                stopped = STOPS_THE_LOOKUPS.contains(e.kind());
                return Optional.empty();
            }
        }
    }

    /** The candidate as a rail item, when it streams on at least one of the household's services. */
    private Optional<ContentItem> trendingItem(TmdbCredential credential, JsonNode candidate, TmdbMediaRef ref,
                                               List<String> keys) {
        if (keys.isEmpty()) {
            return Optional.empty();
        }
        String prefix = "On " + names(keys);
        List<PlayableRef> playables = playablesFor(ref.itemId(), keys);
        return TmdbItemMapper.toItem(candidate, null, path -> images.poster(credential, path), prefix, playables);
    }

    @Override
    public boolean searchable() {
        return true;
    }

    @Override
    public List<ContentItem> search(String query, int limit) {
        TmdbCredential credential = setup.credential()
                .orElseThrow(() -> new ContentSourceException(ContentSourceException.Kind.NOT_CONFIGURED, NOT_CONNECTED));
        LinkedHashMap<String, String> params = new LinkedHashMap<>();
        params.put("query", query);
        params.put(LANGUAGE, preferences.get().locale());
        params.put("include_adult", "false");
        params.put("page", "1");
        JsonNode response = client.get(credential, "/search/multi", params);
        List<ContentItem> items = new ArrayList<>();
        for (JsonNode result : response.path("results")) {
            if (items.size() >= limit) {
                break;
            }
            TmdbItemMapper.toItem(result, null, path -> images.poster(credential, path), null, List.of())
                    .ifPresent(items::add);
        }
        return items;
    }

    @Override
    public Optional<ContentItem> item(String itemId) {
        Optional<TmdbMediaRef> ref = TmdbMediaRef.parse(itemId);
        if (ref.isEmpty()) {
            return Optional.empty();
        }
        TmdbCredential credential = setup.credential()
                .orElseThrow(() -> new ContentSourceException(ContentSourceException.Kind.NOT_CONFIGURED, NOT_CONNECTED));
        TmdbMediaRef mediaRef = ref.orElseThrow();
        SourcePreferences prefs = preferences.get();
        LinkedHashMap<String, String> params = new LinkedHashMap<>();
        params.put(LANGUAGE, prefs.locale());
        params.put("append_to_response", "watch/providers");
        JsonNode body;
        try {
            body = client.get(credential, mediaRef.path(), params);
        } catch (TmdbException e) {
            if (e.kind() == ContentSourceException.Kind.NOT_FOUND) {
                return Optional.empty();
            }
            throw e;
        }
        JsonNode watchProvidersNode = body.path("watch/providers");
        providers.remember(mediaRef, watchProvidersNode);
        List<WatchProvider> watchProviders = WatchProvider.parse(watchProvidersNode, prefs.region());
        List<String> keys = matcher.matches(watchProviders, prefs.providers());
        String prefix = keys.isEmpty() ? null : "On " + names(keys);
        List<PlayableRef> playables = playablesFor(mediaRef.itemId(), keys);
        String hint = mediaRef.type() == TmdbMediaRef.Type.MOVIE ? "movie" : "tv";
        return TmdbItemMapper.toItem(body, hint, path -> images.poster(credential, path), prefix, playables);
    }

    @Override
    public Duration defaultRefreshInterval() {
        return Duration.ofHours(6);
    }

    /** A pinned title link wins; otherwise the first configured service with an app-home URL; otherwise nothing. */
    private List<PlayableRef> playablesFor(String itemId, List<String> keys) {
        PinnedLinks links = pinnedLinks.getIfAvailable();
        if (links != null) {
            Optional<PlayableRef.AppLink> pinned = links.linkFor(SOURCE_ID, itemId);
            if (pinned.isPresent()) {
                return List.of(pinned.get());
            }
        }
        for (String key : keys) {
            Optional<URI> home = ServiceLinks.appHome(key);
            if (home.isPresent()) {
                return List.of(new PlayableRef.AppLink(home.get(), key));
            }
        }
        return List.of();
    }

    private static String names(List<String> keys) {
        return keys.stream().map(key -> StreamingProviders.KNOWN.getOrDefault(key, key))
                .collect(Collectors.joining(", "));
    }

    /**
     * Pages {@code /trending/all/week} collecting movie/series results (never people, never adult),
     * de-duplicated by item id, until {@code trendingCandidates} are collected, the last page is
     * reached, or a page comes back empty.
     */
    private List<JsonNode> collectTrendingCandidates(TmdbCredential credential, String language) {
        List<JsonNode> candidates = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        int page = 1;
        boolean lastPage = false;
        while (!lastPage && candidates.size() < properties.trendingCandidates()) {
            LinkedHashMap<String, String> params = new LinkedHashMap<>();
            params.put(LANGUAGE, language);
            params.put("page", String.valueOf(page));
            JsonNode response = client.get(credential, "/trending/all/week", params);
            int rawCount = addCandidates(response.path("results"), candidates, seenIds);
            int totalPages = Math.max(1, response.path("total_pages").asInt(1));
            lastPage = rawCount == 0 || page >= totalPages;
            page++;
        }
        return candidates;
    }

    /** Adds one page's new titles until the candidates are full; returns how many results it looked at. */
    private int addCandidates(JsonNode results, List<JsonNode> candidates, Set<String> seenIds) {
        int rawCount = 0;
        Iterator<JsonNode> remaining = results.iterator();
        while (remaining.hasNext() && candidates.size() < properties.trendingCandidates()) {
            JsonNode result = remaining.next();
            rawCount++;
            if (isNewTitle(result, seenIds)) {
                candidates.add(result);
            }
        }
        return rawCount;
    }

    /** A movie or series (never a person, never adult) not collected yet; remembers it as seen. */
    private static boolean isNewTitle(JsonNode result, Set<String> seenIds) {
        if (result.path("adult").asBoolean(false)) {
            return false;
        }
        Optional<TmdbMediaRef> ref = TmdbMediaRef.of(result, null);
        return ref.isPresent() && seenIds.add(ref.get().itemId());
    }
}

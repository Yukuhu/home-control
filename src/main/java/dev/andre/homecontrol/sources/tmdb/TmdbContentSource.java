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
import java.util.HashSet;
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

    private final TmdbSetupService setup;
    private final TmdbClient client;
    private final TmdbImages images;
    private final TmdbWatchProviders providers;
    private final TmdbProperties properties;
    private final Supplier<SourcePreferences> preferences;
    private final ProviderMatcher matcher;
    private final ObjectProvider<PinnedLinks> pinnedLinks;
    private final Clock clock;

    public TmdbContentSource(TmdbSetupService setup, TmdbClient client, TmdbImages images,
                             TmdbWatchProviders providers, TmdbProperties properties,
                             Supplier<SourcePreferences> preferences, ProviderMatcher matcher,
                             ObjectProvider<PinnedLinks> pinnedLinks) {
        this(setup, client, images, providers, properties, preferences, matcher, pinnedLinks, Clock.systemUTC());
    }

    /** Package-private: lets tests pin {@code fetchedAt}. */
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
                .orElseThrow(() -> new ContentSourceException(NOT_CONNECTED));
        SourcePreferences prefs = preferences.get();
        List<String> configuredProviders = prefs.providers();
        if (configuredProviders.isEmpty()) {
            throw new ContentSourceException("Choose your streaming services in Setup to see what is trending on them");
        }
        List<JsonNode> candidates = collectTrendingCandidates(credential, prefs.locale());
        List<ContentItem> items = new ArrayList<>();
        TmdbException firstFailure = null;
        boolean anyAttempted = false;
        boolean anyLookupSucceeded = false;
        for (JsonNode candidate : candidates) {
            if (items.size() >= properties.railSize()) {
                break;
            }
            TmdbMediaRef ref = TmdbMediaRef.of(candidate, null).orElseThrow();
            anyAttempted = true;
            List<WatchProvider> watchProviders;
            try {
                watchProviders = providers.providers(credential, ref, prefs.region());
                anyLookupSucceeded = true;
            } catch (TmdbException e) {
                if (firstFailure == null) {
                    firstFailure = e;
                }
                continue;
            }
            List<String> keys = matcher.matches(watchProviders, configuredProviders);
            if (keys.isEmpty()) {
                continue;
            }
            String prefix = "On " + names(keys);
            List<PlayableRef> playables = playablesFor(ref.itemId(), keys);
            TmdbItemMapper.toItem(candidate, null, path -> images.poster(credential, path), prefix, playables)
                    .ifPresent(items::add);
        }
        if (anyAttempted && !anyLookupSucceeded && firstFailure != null) {
            throw firstFailure;
        }
        return new Rail(TRENDING, items, clock.instant());
    }

    @Override
    public boolean searchable() {
        return true;
    }

    @Override
    public List<ContentItem> search(String query, int limit) {
        TmdbCredential credential = setup.credential()
                .orElseThrow(() -> new ContentSourceException(NOT_CONNECTED));
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
                .orElseThrow(() -> new ContentSourceException(NOT_CONNECTED));
        TmdbMediaRef mediaRef = ref.orElseThrow();
        SourcePreferences prefs = preferences.get();
        LinkedHashMap<String, String> params = new LinkedHashMap<>();
        params.put(LANGUAGE, prefs.locale());
        params.put("append_to_response", "watch/providers");
        JsonNode body;
        try {
            body = client.get(credential, mediaRef.path(), params);
        } catch (TmdbException e) {
            if (e.kind() == TmdbException.Kind.NOT_FOUND) {
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
        while (candidates.size() < properties.trendingCandidates()) {
            LinkedHashMap<String, String> params = new LinkedHashMap<>();
            params.put(LANGUAGE, language);
            params.put("page", String.valueOf(page));
            JsonNode response = client.get(credential, "/trending/all/week", params);
            int rawCount = 0;
            for (JsonNode result : response.path("results")) {
                rawCount++;
                if (result.path("adult").asBoolean(false)) {
                    continue;
                }
                Optional<TmdbMediaRef> ref = TmdbMediaRef.of(result, null);
                if (ref.isEmpty()) {
                    continue;
                }
                if (!seenIds.add(ref.get().itemId())) {
                    continue;
                }
                candidates.add(result);
                if (candidates.size() >= properties.trendingCandidates()) {
                    break;
                }
            }
            int totalPages = Math.max(1, response.path("total_pages").asInt(1));
            if (rawCount == 0 || page >= totalPages) {
                break;
            }
            page++;
        }
        return candidates;
    }
}

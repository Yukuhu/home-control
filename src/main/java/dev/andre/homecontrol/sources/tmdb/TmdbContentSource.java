package dev.andre.homecontrol.sources.tmdb;

import dev.andre.homecontrol.core.content.ContentSource;
import dev.andre.homecontrol.core.content.ContentSourceException;
import dev.andre.homecontrol.core.content.Rail;
import dev.andre.homecontrol.core.content.RailDescriptor;
import dev.andre.homecontrol.core.content.SourcePreferences;
import dev.andre.homecontrol.core.playback.ContentItem;
import dev.andre.homecontrol.core.playback.PlayableRef;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/** TMDB: titles, artwork and where they stream. No rails yet — Task 4 adds trending. */
public class TmdbContentSource implements ContentSource {

    public static final String ID = "tmdb";

    private final TmdbSetupService setup;
    private final TmdbClient client;
    private final TmdbImages images;
    private final TmdbWatchProviders providers;
    private final TmdbProperties properties;
    private final Supplier<SourcePreferences> preferences;

    public TmdbContentSource(TmdbSetupService setup, TmdbClient client, TmdbImages images,
                             TmdbWatchProviders providers, TmdbProperties properties,
                             Supplier<SourcePreferences> preferences) {
        this.setup = setup;
        this.client = client;
        this.images = images;
        this.providers = providers;
        this.properties = properties;
        this.preferences = preferences;
    }

    @Override
    public String id() {
        return ID;
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
        return List.of();
    }

    @Override
    public Rail rail(String railId) {
        throw new IllegalArgumentException("TMDB has no rail '" + railId + "'");
    }

    @Override
    public boolean searchable() {
        return true;
    }

    @Override
    public List<ContentItem> search(String query, int limit) {
        TmdbCredential credential = setup.credential()
                .orElseThrow(() -> new ContentSourceException("TMDB is not connected"));
        LinkedHashMap<String, String> params = new LinkedHashMap<>();
        params.put("query", query);
        params.put("language", preferences.get().locale());
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
                .orElseThrow(() -> new ContentSourceException("TMDB is not connected"));
        TmdbMediaRef mediaRef = ref.orElseThrow();
        LinkedHashMap<String, String> params = new LinkedHashMap<>();
        params.put("language", preferences.get().locale());
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
        providers.remember(mediaRef, body.path("watch/providers"));
        String hint = mediaRef.type() == TmdbMediaRef.Type.MOVIE ? "movie" : "tv";
        List<PlayableRef> playables = List.of();
        return TmdbItemMapper.toItem(body, hint, path -> images.poster(credential, path), null, playables);
    }

    @Override
    public Duration defaultRefreshInterval() {
        return Duration.ofHours(6);
    }
}

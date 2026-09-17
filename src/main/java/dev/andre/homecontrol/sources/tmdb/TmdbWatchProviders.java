package dev.andre.homecontrol.sources.tmdb;

import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Watch-provider lookups, cached per (title, region) so repeated reads of the same rail or item
 * don't hammer TMDB. A remembered {@code watch/providers} sub-document (e.g. from a details call
 * that already asked for it via {@code append_to_response}) avoids a lookup entirely.
 */
public class TmdbWatchProviders {

    private static final int MAX_ENTRIES = 2000;

    private record Entry(JsonNode providers, Instant fetchedAt) {
    }

    private final TmdbClient client;
    private final TmdbProperties properties;
    private final Clock clock;

    /** Access order, so the eldest (least recently used) entry is evicted first. */
    private final Map<TmdbMediaRef, Entry> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<TmdbMediaRef, Entry> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    public TmdbWatchProviders(TmdbClient client, TmdbProperties properties, Clock clock) {
        this.client = client;
        this.properties = properties;
        this.clock = clock;
    }

    public List<WatchProvider> providers(TmdbCredential credential, TmdbMediaRef ref, String region) {
        JsonNode cached = fresh(ref);
        if (cached != null) {
            return WatchProvider.parse(cached, region);
        }
        JsonNode fetched = client.get(credential, ref.path() + "/watch/providers", Map.of());
        remember(ref, fetched);
        return WatchProvider.parse(fetched, region);
    }

    /** Stores a {@code watch/providers} sub-document obtained incidentally (e.g. from a details call). */
    public void remember(TmdbMediaRef ref, JsonNode providers) {
        if (providers != null && providers.isObject()) {
            synchronized (this) {
                cache.put(ref, new Entry(providers, clock.instant()));
            }
        }
    }

    private synchronized JsonNode fresh(TmdbMediaRef ref) {
        Entry entry = cache.get(ref);
        if (entry == null) {
            return null;
        }
        if (entry.fetchedAt().plus(properties.providerCacheTtl()).isBefore(clock.instant())) {
            return null;
        }
        return entry.providers();
    }
}

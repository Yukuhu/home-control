package dev.andre.homecontrol.sources.youtube;

import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** search.list, 100 units per call, capped per day, cached so repeating a query costs nothing. */
public class YouTubeSearch {

    private static final int MAX_RESULTS = 25;
    private static final int CACHE_ENTRIES = 50;
    private static final Pattern ENTITY = Pattern.compile("&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|amp|lt|gt|quot|apos|#39);");

    private record Cached(List<YouTubeVideo> videos, Instant at) {
    }

    private final YouTubeApiClient api;
    private final KnownVideos known;
    private final YouTubeProperties properties;
    private final Clock clock;
    private final Map<String, Cached> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Cached> eldest) {
            return size() > CACHE_ENTRIES;
        }
    };

    public YouTubeSearch(YouTubeApiClient api, KnownVideos known, YouTubeProperties properties, Clock clock) {
        this.api = api;
        this.known = known;
        this.properties = properties;
        this.clock = clock;
    }

    public List<YouTubeVideo> search(String query, int limit) {
        String key = cacheKey(query);
        int wanted = Math.min(Math.max(limit, 1), MAX_RESULTS);
        Optional<List<YouTubeVideo>> hit = cached(key, wanted);
        if (hit.isPresent()) {
            return hit.get();
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put("part", "snippet");
        params.put("type", "video");
        params.put("maxResults", String.valueOf(wanted));
        params.put("q", query.strip());
        JsonNode response = api.get(QuotaLedger.Call.SEARCH_LIST, "search", params);
        List<YouTubeVideo> videos = new ArrayList<>();
        for (JsonNode item : response.path("items")) {
            YouTubeVideoMapper.fromSearchResult(item).ifPresent(videos::add);
        }
        known.remember(videos);
        synchronized (cache) {
            cache.put(key, new Cached(List.copyOf(videos), clock.instant()));
        }
        return videos.stream().limit(wanted).toList();
    }

    private Optional<List<YouTubeVideo>> cached(String key, int wanted) {
        synchronized (cache) {
            Cached entry = cache.get(key);
            if (entry == null || !clock.instant().isBefore(entry.at().plus(properties.searchCacheTtl()))) {
                return Optional.empty();
            }
            return Optional.of(entry.videos().stream().limit(wanted).toList());
        }
    }

    public static String cacheKey(String query) {
        return query.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    public static String unescapeHtml(String text) {
        Matcher matcher = ENTITY.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String entity = matcher.group(1);
            String replacement = switch (entity) {
                case "amp" -> "&";
                case "lt" -> "<";
                case "gt" -> ">";
                case "quot" -> "\"";
                case "apos", "#39" -> "'";
                default -> {
                    int codePoint = entity.startsWith("#x") || entity.startsWith("#X")
                            ? Integer.parseInt(entity.substring(2), 16)
                            : Integer.parseInt(entity.substring(1));
                    yield Character.isValidCodePoint(codePoint) ? new String(Character.toChars(codePoint)) : matcher.group();
                }
            };
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}

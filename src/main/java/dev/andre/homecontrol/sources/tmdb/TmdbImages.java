package dev.andre.homecontrol.sources.tmdb;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Turns a TMDB {@code poster_path} into a full image URL, using the configured or discovered image base. */
public class TmdbImages {

    private static final Logger log = LoggerFactory.getLogger(TmdbImages.class);

    static final String DEFAULT_BASE = "https://image.tmdb.org/t/p/";
    static final String PREFERRED_SIZE = "w342";
    static final Duration RETRY_AFTER_FAILURE = Duration.ofMinutes(10);
    static final Pattern POSTER_PATH = Pattern.compile("^/[A-Za-z0-9_-]+\\.(jpg|jpeg|png|webp|svg)$");

    private record Base(String base, String size, Instant validUntil) {
    }

    private final TmdbClient client;
    private final TmdbProperties properties;
    private final Clock clock;
    private volatile Base override;
    private volatile Base cached;

    public TmdbImages(TmdbClient client, TmdbProperties properties, Clock clock) {
        this.client = client;
        this.properties = properties;
        this.clock = clock;
        if (properties.imageBaseUrl() != null) {
            String base = properties.imageBaseUrl().toString();
            this.override = new Base(base.endsWith("/") ? base : base + "/", PREFERRED_SIZE, Instant.MAX);
        }
    }

    public URI poster(TmdbCredential credential, String posterPath) {
        if (posterPath == null || !POSTER_PATH.matcher(posterPath).matches()) {
            return null;
        }
        Base base = override != null ? override : base(credential);
        return URI.create(base.base() + base.size() + posterPath);
    }

    private Base base(TmdbCredential credential) {
        Base current = cached;
        Instant now = clock.instant();
        if (current != null && current.validUntil().isAfter(now)) {
            return current;
        }
        Base refreshed;
        try {
            JsonNode configuration = client.get(credential, "/configuration", Map.of());
            JsonNode images = configuration.path("images");
            String secureBase = images.path("secure_base_url").asString("");
            String base = secureBase.startsWith("https://") ? secureBase : DEFAULT_BASE;
            if (!base.endsWith("/")) {
                base = base + "/";
            }
            String size = pickSize(images.path("poster_sizes"));
            refreshed = new Base(base, size, now.plus(properties.configurationCacheTtl()));
        } catch (TmdbException e) {
            log.warn("Could not read TMDB's image configuration: {}", e.getMessage());
            refreshed = new Base(DEFAULT_BASE, PREFERRED_SIZE, now.plus(RETRY_AFTER_FAILURE));
        }
        cached = refreshed;
        return refreshed;
    }

    /** TMDB's documented poster sizes, smallest to largest; used to pick the smallest one at least as big as {@link #PREFERRED_SIZE}. */
    private static final List<String> CANONICAL_SIZES = List.of("w92", "w154", "w185", "w342", "w500", "w780", "original");

    private static String pickSize(JsonNode posterSizes) {
        List<String> sizes = posterSizes.valueStream().map(node -> node.asString("")).toList();
        if (sizes.contains(PREFERRED_SIZE)) {
            return PREFERRED_SIZE;
        }
        int preferredIndex = CANONICAL_SIZES.indexOf(PREFERRED_SIZE);
        for (int i = preferredIndex; i < CANONICAL_SIZES.size(); i++) {
            if (sizes.contains(CANONICAL_SIZES.get(i))) {
                return CANONICAL_SIZES.get(i);
            }
        }
        return sizes.isEmpty() ? PREFERRED_SIZE : sizes.getLast();
    }
}

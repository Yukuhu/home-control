package dev.andre.homecontrol.sources.tmdb;

import tools.jackson.databind.JsonNode;

import java.util.Optional;
import java.util.regex.Pattern;

/** A TMDB movie or TV series, addressed the way Home Control's item ids do: {@code movie-603}, {@code tv-66732}. */
public record TmdbMediaRef(Type type, long id) {

    public enum Type { MOVIE, TV }
    private static final String MOVIE_VALUE = "movie";

    private static final Pattern ITEM_ID = Pattern.compile("^(movie|tv)-([1-9][0-9]{0,9})$");

    public String itemId() {
        return (type == Type.MOVIE ? MOVIE_VALUE : "tv") + "-" + id;
    }

    public String path() {
        return "/" + (type == Type.MOVIE ? MOVIE_VALUE : "tv") + "/" + id;
    }

    public static Optional<TmdbMediaRef> parse(String itemId) {
        if (itemId == null) {
            return Optional.empty();
        }
        var matcher = ITEM_ID.matcher(itemId);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        Type type = MOVIE_VALUE.equals(matcher.group(1)) ? Type.MOVIE : Type.TV;
        try {
            return Optional.of(new TmdbMediaRef(type, Long.parseLong(matcher.group(2))));
        } catch (NumberFormatException _) {
            return Optional.empty();
        }
    }

    public static Optional<TmdbMediaRef> of(JsonNode result, String mediaTypeHint) {
        String mediaType = result.path("media_type").asString(mediaTypeHint == null ? "" : mediaTypeHint);
        Type type;
        if (MOVIE_VALUE.equals(mediaType)) {
            type = Type.MOVIE;
        } else if ("tv".equals(mediaType)) {
            type = Type.TV;
        } else {
            return Optional.empty();
        }
        JsonNode idNode = result.path("id");
        if (!idNode.isIntegralNumber() || idNode.asLong(0) <= 0) {
            return Optional.empty();
        }
        return Optional.of(new TmdbMediaRef(type, idNode.asLong(0)));
    }
}

package dev.andre.homecontrol.sources.tmdb;

import dev.andre.homecontrol.core.playback.ContentItem;
import dev.andre.homecontrol.core.playback.ContentKind;
import dev.andre.homecontrol.core.playback.PlayableRef;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;

/** A TMDB search/trending/details result → a {@link ContentItem}, or empty when it is not a title. */
public final class TmdbItemMapper {

    private static final Pattern YEAR = Pattern.compile("^\\d{4}-");

    private TmdbItemMapper() {
    }

    public static Optional<ContentItem> toItem(JsonNode result, String mediaTypeHint,
                                               Function<String, URI> posters, String subtitlePrefix,
                                               List<PlayableRef> playables) {
        Optional<TmdbMediaRef> ref = TmdbMediaRef.of(result, mediaTypeHint);
        if (ref.isEmpty()) {
            return Optional.empty();
        }
        if (result.path("adult").asBoolean(false)) {
            return Optional.empty();
        }
        TmdbMediaRef mediaRef = ref.orElseThrow();
        boolean movie = mediaRef.type() == TmdbMediaRef.Type.MOVIE;
        String title = movie
                ? firstNonBlank(result.path("title"), result.path("original_title"))
                : firstNonBlank(result.path("name"), result.path("original_name"));
        if (title == null) {
            return Optional.empty();
        }
        String dateField = movie ? "release_date" : "first_air_date";
        String date = result.path(dateField).asString("");
        String year = YEAR.matcher(date).lookingAt() ? date.substring(0, 4) : null;
        String subtitle;
        if (subtitlePrefix != null) {
            subtitle = year != null ? subtitlePrefix + " · " + year : subtitlePrefix;
        } else {
            String kindWord = movie ? "Movie" : "Series";
            subtitle = year != null ? kindWord + " · " + year : kindWord;
        }
        String posterPath = result.path("poster_path").isString() ? result.path("poster_path").asString() : null;
        URI artwork = posterPath == null ? null : posters.apply(posterPath);
        ContentKind kind = movie ? ContentKind.MOVIE : ContentKind.VIDEO;
        return Optional.of(new ContentItem(mediaRef.itemId(), TmdbSettings.SOURCE_ID, kind, title, subtitle,
                artwork, playables, null));
    }

    private static String firstNonBlank(JsonNode primary, JsonNode fallback) {
        String value = primary.isString() ? primary.asString() : "";
        if (!value.isBlank()) {
            return value;
        }
        String fallbackValue = fallback.isString() ? fallback.asString() : "";
        return fallbackValue.isBlank() ? null : fallbackValue;
    }
}

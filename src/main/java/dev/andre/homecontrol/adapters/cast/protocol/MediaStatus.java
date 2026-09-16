package dev.andre.homecontrol.adapters.cast.protocol;

import dev.andre.homecontrol.core.PlaybackState;
import tools.jackson.databind.JsonNode;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** One entry of a MEDIA_STATUS {@code status} array. Nullable fields were absent. */
public record MediaStatus(long mediaSessionId, String playerState, double currentTime, String contentId,
                          String title, Double duration, String idleReason) {

    public static List<MediaStatus> parse(JsonNode statusArray) {
        List<MediaStatus> statuses = new ArrayList<>();
        for (JsonNode entry : statusArray) {
            JsonNode media = entry.path("media");
            JsonNode metadata = media.path("metadata");
            statuses.add(new MediaStatus(
                    entry.path("mediaSessionId").asLong(0),
                    entry.path("playerState").asString("IDLE"),
                    entry.path("currentTime").asDouble(0.0),
                    media.has("contentId") ? media.path("contentId").asString("") : null,
                    metadata.has("title") ? metadata.path("title").asString("") : null,
                    media.path("duration").isNumber() ? media.path("duration").asDouble(0.0) : null,
                    entry.has("idleReason") ? entry.path("idleReason").asString("") : null));
        }
        return List.copyOf(statuses);
    }

    /** Receivers send {@code media} once per session; later statuses inherit it. */
    public MediaStatus fillFrom(MediaStatus previous) {
        if (previous == null || previous.mediaSessionId != mediaSessionId) {
            return this;
        }
        return new MediaStatus(mediaSessionId, playerState, currentTime,
                contentId != null ? contentId : previous.contentId,
                title != null ? title : previous.title,
                duration != null ? duration : previous.duration,
                idleReason);
    }

    /** LOADING and anything a newer receiver invents count as buffering. */
    public PlaybackState playbackState() {
        return switch (playerState) {
            case "PLAYING" -> PlaybackState.PLAYING;
            case "PAUSED" -> PlaybackState.PAUSED;
            case "IDLE" -> PlaybackState.IDLE;
            case null, default -> PlaybackState.BUFFERING;
        };
    }

    /** The title, else the URL's file name, else a placeholder. */
    public String displayTitle() {
        if (title != null && !title.isBlank()) {
            return title;
        }
        if (contentId != null && !contentId.isBlank()) {
            String path = contentId.split("[?#]", 2)[0];
            String name = path.substring(path.lastIndexOf('/') + 1);
            if (!name.isBlank()) {
                // A path "+" is a literal plus; URLDecoder would read it as a form-encoded space.
                return URLDecoder.decode(name.replace("+", "%2B"), StandardCharsets.UTF_8);
            }
        }
        return "Unknown media";
    }
}

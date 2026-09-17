package dev.andre.homecontrol.sources.youtube;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Turns YouTube Data API v3 JSON into {@link YouTubeVideo}, dropping anything unavailable or malformed. */
final class YouTubeVideoMapper {

    private YouTubeVideoMapper() {
    }

    static Optional<YouTubeVideo> fromPlaylistItem(JsonNode item) {
        JsonNode snippet = item.path("snippet");
        JsonNode contentDetails = item.path("contentDetails");
        String id = contentDetails.path("videoId").asString("");
        if (id.isBlank()) {
            id = snippet.path("resourceId").path("videoId").asString("");
        }
        if (!YouTubeVideo.validId(id)) {
            return Optional.empty();
        }
        String title = snippet.path("title").asString("");
        if ("Private video".equals(title) || "Deleted video".equals(title)) {
            return Optional.empty();
        }
        String ownerChannelId = snippet.path("videoOwnerChannelId").asString("");
        if (ownerChannelId.isBlank()) {
            return Optional.empty();
        }
        String channelTitle = snippet.path("videoOwnerChannelTitle").asString("");
        if (channelTitle.isBlank()) {
            channelTitle = snippet.path("channelTitle").asString("");
        }
        String primaryPublished = contentDetails.path("videoPublishedAt").asString("");
        Instant published = primaryPublished.isBlank()
                ? instantOrEpoch(snippet.path("publishedAt").asString(""))
                : instantOrEpoch(primaryPublished);
        return Optional.of(new YouTubeVideo(id, title, channelTitle, published));
    }

    static Optional<YouTubeVideo> fromVideo(JsonNode item) {
        String id = item.path("id").asString("");
        if (!YouTubeVideo.validId(id)) {
            return Optional.empty();
        }
        JsonNode snippet = item.path("snippet");
        String title = snippet.path("title").asString("");
        String channelTitle = snippet.path("channelTitle").asString("");
        Instant published = instantOrEpoch(snippet.path("publishedAt").asString(""));
        return Optional.of(new YouTubeVideo(id, title, channelTitle, published));
    }

    static List<YouTubeVideo> playlistItems(JsonNode response) {
        List<YouTubeVideo> videos = new ArrayList<>();
        for (JsonNode item : response.path("items")) {
            fromPlaylistItem(item).ifPresent(videos::add);
        }
        return videos;
    }

    private static Instant instantOrEpoch(String value) {
        if (value == null || value.isBlank()) {
            return Instant.EPOCH;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            return Instant.EPOCH;
        }
    }
}

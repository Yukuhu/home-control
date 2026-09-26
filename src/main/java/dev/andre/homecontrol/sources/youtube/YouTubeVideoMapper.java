package dev.andre.homecontrol.sources.youtube;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Turns YouTube Data API v3 JSON into {@link YouTubeVideo}, dropping anything unavailable or malformed. */
final class YouTubeVideoMapper {

    private static final String SNIPPET = "snippet";
    private static final String VIDEO_ID = "videoId";
    private static final String TITLE = "title";
    private static final String CHANNEL_TITLE = "channelTitle";
    private static final String PUBLISHED_AT = "publishedAt";

    private YouTubeVideoMapper() {
    }

    static Optional<YouTubeVideo> fromPlaylistItem(JsonNode item) {
        JsonNode snippet = item.path(SNIPPET);
        JsonNode contentDetails = item.path("contentDetails");
        String id = contentDetails.path(VIDEO_ID).asString("");
        if (id.isBlank()) {
            id = snippet.path("resourceId").path(VIDEO_ID).asString("");
        }
        if (!YouTubeVideo.validId(id)) {
            return Optional.empty();
        }
        String title = snippet.path(TITLE).asString("");
        if ("Private video".equals(title) || "Deleted video".equals(title)) {
            return Optional.empty();
        }
        String ownerChannelId = snippet.path("videoOwnerChannelId").asString("");
        if (ownerChannelId.isBlank()) {
            return Optional.empty();
        }
        String channelTitle = snippet.path("videoOwnerChannelTitle").asString("");
        if (channelTitle.isBlank()) {
            channelTitle = snippet.path(CHANNEL_TITLE).asString("");
        }
        // A malformed (not just missing) contentDetails.videoPublishedAt still falls back to
        // snippet.publishedAt before giving up on Instant.EPOCH — a bad-but-present primary field
        // shouldn't be treated any differently from an absent one.
        Instant published = parseInstant(contentDetails.path("videoPublishedAt").asString(""))
                .or(() -> parseInstant(snippet.path(PUBLISHED_AT).asString("")))
                .orElse(Instant.EPOCH);
        return Optional.of(new YouTubeVideo(id, title, channelTitle, published));
    }

    static Optional<YouTubeVideo> fromVideo(JsonNode item) {
        String id = item.path("id").asString("");
        if (!YouTubeVideo.validId(id)) {
            return Optional.empty();
        }
        JsonNode snippet = item.path(SNIPPET);
        String title = snippet.path(TITLE).asString("");
        String channelTitle = snippet.path(CHANNEL_TITLE).asString("");
        Instant published = instantOrEpoch(snippet.path(PUBLISHED_AT).asString(""));
        return Optional.of(new YouTubeVideo(id, title, channelTitle, published));
    }

    static Optional<YouTubeVideo> fromSearchResult(JsonNode item) {
        String id = item.path("id").path(VIDEO_ID).asString("");
        if (!YouTubeVideo.validId(id)) {
            return Optional.empty();
        }
        JsonNode snippet = item.path(SNIPPET);
        if ("upcoming".equals(snippet.path("liveBroadcastContent").asString(""))) {
            return Optional.empty();
        }
        String title = YouTubeSearch.unescapeHtml(snippet.path(TITLE).asString(""));
        String channelTitle = YouTubeSearch.unescapeHtml(snippet.path(CHANNEL_TITLE).asString(""));
        Instant published = instantOrEpoch(snippet.path(PUBLISHED_AT).asString(""));
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
        return parseInstant(value).orElse(Instant.EPOCH);
    }

    private static Optional<Instant> parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Instant.parse(value));
        } catch (DateTimeParseException _) {
            return Optional.empty();
        }
    }
}

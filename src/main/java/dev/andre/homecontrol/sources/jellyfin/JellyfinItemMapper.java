package dev.andre.homecontrol.sources.jellyfin;

import dev.andre.homecontrol.core.playback.ContentItem;
import dev.andre.homecontrol.core.playback.ContentKind;
import dev.andre.homecontrol.core.playback.PlayableRef;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Jellyfin BaseItemDto → ContentItem. Only token-free references are attached. */
public final class JellyfinItemMapper {

    public static final String IMAGE_PATH = "/sources/jellyfin/images/";

    private JellyfinItemMapper() {
    }

    public static List<ContentItem> toItems(JsonNode array) {
        List<ContentItem> items = new ArrayList<>();
        for (JsonNode node : array) {
            toItem(node).ifPresent(items::add);
        }
        return items;
    }

    public static Optional<ContentItem> toItem(JsonNode item) {
        String id = item.path("Id").asString("");
        if (id.isBlank() || item.path("IsFolder").asBoolean(false)) {
            return Optional.empty();
        }
        ContentKind kind = switch (item.path("Type").asString("")) {
            case "Movie" -> ContentKind.MOVIE;
            case "Episode" -> ContentKind.EPISODE;
            case "Audio" -> ContentKind.TRACK;
            default -> ContentKind.VIDEO;
        };
        String name = item.path("Name").asString("");
        String title;
        String subtitle;
        switch (kind) {
            case EPISODE -> {
                title = item.path("SeriesName").asString(name);
                subtitle = episodeLabel(item, name);
            }
            case TRACK -> {
                title = name;
                subtitle = artists(item);
            }
            default -> {
                title = name;
                int year = item.path("ProductionYear").asInt(0);
                subtitle = year > 0 ? String.valueOf(year) : null;
            }
        }
        JsonNode userData = item.path("UserData");
        long position = Math.max(0, userData.path("PlaybackPositionTicks").asLong(0));
        return Optional.of(new ContentItem(id, JellyfinSettings.SOURCE_ID, kind, title, subtitle, artwork(item),
                List.of(new PlayableRef.JellyfinItem(item.path("ServerId").asString(""), id, position)),
                progress(item, userData)));
    }

    static String episodeLabel(JsonNode item, String name) {
        int season = item.path("ParentIndexNumber").asInt(-1);
        int episode = item.path("IndexNumber").asInt(-1);
        String number = episode < 0 ? null : season < 0 ? "E" + episode : "S" + season + ":E" + episode;
        if (number == null) {
            return name.isBlank() ? null : name;
        }
        return name.isBlank() ? number : number + " · " + name;
    }

    static String artists(JsonNode item) {
        List<String> names = new ArrayList<>();
        for (JsonNode artist : item.path("Artists")) {
            String value = artist.asString("");
            if (!value.isBlank()) {
                names.add(value);
            }
        }
        if (!names.isEmpty()) {
            return String.join(", ", names);
        }
        String albumArtist = item.path("AlbumArtist").asString("");
        return albumArtist.isBlank() ? null : albumArtist;
    }

    static Double progress(JsonNode item, JsonNode userData) {
        if (userData.path("Played").asBoolean(false)) {
            return null;
        }
        double percentage = userData.path("PlayedPercentage").asDouble(0.0);
        if (percentage > 0) {
            return Math.min(1.0, percentage / 100.0);
        }
        long position = userData.path("PlaybackPositionTicks").asLong(0);
        long runtime = item.path("RunTimeTicks").asLong(0);
        return position > 0 && runtime > 0 ? Math.min(1.0, (double) position / runtime) : null;
    }

    static URI artwork(JsonNode item) {
        String own = item.path("ImageTags").path("Primary").asString("");
        if (!own.isBlank()) {
            return image(item.path("Id").asString(""), own);
        }
        String seriesId = item.path("SeriesId").asString("");
        String seriesTag = item.path("SeriesPrimaryImageTag").asString("");
        if (!seriesId.isBlank() && !seriesTag.isBlank()) {
            return image(seriesId, seriesTag);
        }
        String albumId = item.path("AlbumId").asString("");
        String albumTag = item.path("AlbumPrimaryImageTag").asString("");
        if (!albumId.isBlank() && !albumTag.isBlank()) {
            return image(albumId, albumTag);
        }
        return null;
    }

    private static URI image(String itemId, String tag) {
        return URI.create(IMAGE_PATH + itemId + "/Primary?tag=" + URLEncoder.encode(tag, StandardCharsets.UTF_8));
    }
}

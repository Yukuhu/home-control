package dev.andre.homecontrol.sources.youtube;

import dev.andre.homecontrol.core.playback.ContentItem;
import dev.andre.homecontrol.core.playback.ContentKind;
import dev.andre.homecontrol.core.playback.PlayableRef;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

/** One video from a subscription's uploads playlist, or fetched directly by id. */
public record YouTubeVideo(String id, String title, String channelTitle, Instant publishedAt) {

    public static final String SOURCE_ID = "youtube";
    private static final Pattern VIDEO_ID = Pattern.compile("[A-Za-z0-9_-]{11}");

    public static boolean validId(String id) {
        return id != null && VIDEO_ID.matcher(id).matches();
    }

    public static URI watchUrl(String id) {
        return URI.create("https://www.youtube.com/watch?v=" + id);
    }

    public ContentItem toItem() {
        return new ContentItem(id, SOURCE_ID, ContentKind.VIDEO, title, channelTitle == null || channelTitle.isBlank() ? null : channelTitle,
                URI.create("/sources/youtube/thumbnails/" + id), List.of(new PlayableRef.AppLink(watchUrl(id), "youtube")), null);
    }
}

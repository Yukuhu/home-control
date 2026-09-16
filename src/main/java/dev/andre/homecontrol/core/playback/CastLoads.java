package dev.andre.homecontrol.core.playback;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** LOAD bodies for {@link PlayableRef.CastLoad} and {@code Action.CastLoad}. */
public final class CastLoads {

    /** Google's Default Media Receiver: plays a URL the receiver can fetch directly. */
    public static final String DEFAULT_MEDIA_RECEIVER = "CC1AD845";

    private CastLoads() {
    }

    /**
     * Both {@code contentId} and {@code contentUrl} carry the URL: CAF receivers prefer
     * {@code contentUrl}, older ones only read {@code contentId}. Generic metadata (type 0).
     */
    public static Map<String, Object> defaultMediaReceiver(PlayableRef.StreamUrl stream, String title) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("metadataType", 0);
        if (title != null && !title.isBlank()) {
            metadata.put("title", title);
        }
        Map<String, Object> media = new LinkedHashMap<>();
        media.put("contentId", stream.url().toString());
        media.put("contentUrl", stream.url().toString());
        media.put("contentType", Objects.requireNonNullElse(stream.mimeType(), "video/mp4"));
        media.put("streamType", "BUFFERED");
        media.put("metadata", metadata);
        Map<String, Object> load = new LinkedHashMap<>();
        load.put("media", media);
        load.put("autoplay", true);
        load.put("currentTime", 0);
        return Collections.unmodifiableMap(load);
    }
}

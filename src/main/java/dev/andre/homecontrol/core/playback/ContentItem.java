package dev.andre.homecontrol.core.playback;

import java.net.URI;
import java.util.List;

/**
 * What a content source produces and a rail shows. {@code subtitle}, {@code artwork} and
 * {@code progress} (fraction watched, 0-1) may be null. {@code artwork} may be a relative URI
 * served by this application.
 */
public record ContentItem(String id, String sourceId, ContentKind kind, String title, String subtitle,
                          URI artwork, List<PlayableRef> playables, Double progress) {

    public ContentItem {
        playables = playables == null ? List.of() : List.copyOf(playables);
        if (progress != null && (progress.isNaN() || progress < 0.0 || progress > 1.0)) {
            throw new IllegalArgumentException("progress must be between 0 and 1");
        }
    }

    public ContentItem(String id, String sourceId, ContentKind kind, String title, String subtitle,
                       URI artwork, List<PlayableRef> playables) {
        this(id, sourceId, kind, title, subtitle, artwork, playables, null);
    }
}

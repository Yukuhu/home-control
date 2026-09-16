package dev.andre.homecontrol.core.playback;

import java.net.URI;
import java.util.List;

/** What a content source produces and a rail shows. {@code subtitle} and {@code artwork} may be null. */
public record ContentItem(String id, String sourceId, ContentKind kind, String title, String subtitle,
                          URI artwork, List<PlayableRef> playables) {

    public ContentItem {
        playables = playables == null ? List.of() : List.copyOf(playables);
    }
}

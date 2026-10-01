package dev.andre.homecontrol.core.playback;

import java.net.URI;
import java.time.Instant;
import java.util.List;

/**
 * What a content source produces and a rail shows. {@code subtitle}, {@code artwork} and
 * {@code progress} (fraction watched, 0-1) may be null. {@code artwork} may be a relative URI
 * served by this application. {@code startsAt}/{@code endsAt} are set for scheduled items such as
 * live events.
 */
public record ContentItem(String id, String sourceId, ContentKind kind, String title, String subtitle,
                          URI artwork, List<PlayableRef> playables, Double progress,
                          Instant startsAt, Instant endsAt) {

    public ContentItem {
        playables = playables == null ? List.of() : List.copyOf(playables);
        if (progress != null && (progress.isNaN() || progress < 0.0 || progress > 1.0)) {
            throw new IllegalArgumentException("progress must be between 0 and 1");
        }
        if (endsAt != null && startsAt == null) {
            throw new IllegalArgumentException("endsAt needs startsAt");
        }
        if (endsAt != null && endsAt.isBefore(startsAt)) {
            throw new IllegalArgumentException("endsAt must not be before startsAt");
        }
    }

    public ContentItem(String id, String sourceId, ContentKind kind, String title, String subtitle,
                       URI artwork, List<PlayableRef> playables, Double progress) {
        this(id, sourceId, kind, title, subtitle, artwork, playables, progress, null, null);
    }

    public ContentItem(String id, String sourceId, ContentKind kind, String title, String subtitle,
                       URI artwork, List<PlayableRef> playables) {
        this(id, sourceId, kind, title, subtitle, artwork, playables, null, null, null);
    }

    public ContentItem withPlayables(List<PlayableRef> replacement) {
        return new ContentItem(id, sourceId, kind, title, subtitle, artwork, replacement, progress, startsAt, endsAt);
    }
}

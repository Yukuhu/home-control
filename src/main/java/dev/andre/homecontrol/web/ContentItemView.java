package dev.andre.homecontrol.web;

import dev.andre.homecontrol.core.playback.ContentItem;

/** The only shape in which content reaches the browser: no playable references, so no secrets. */
public record ContentItemView(String id, String sourceId, String kind, String title, String subtitle,
                              String artwork, Double progress, String startsAt, String endsAt) {

    public static ContentItemView of(ContentItem item) {
        return new ContentItemView(item.id(), item.sourceId(), item.kind().name(), item.title(), item.subtitle(),
                item.artwork() == null ? null : item.artwork().toString(), item.progress(),
                item.startsAt() == null ? null : item.startsAt().toString(),
                item.endsAt() == null ? null : item.endsAt().toString());
    }
}

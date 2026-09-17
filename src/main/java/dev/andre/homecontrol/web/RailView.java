package dev.andre.homecontrol.web;

import dev.andre.homecontrol.content.RailSnapshot;
import dev.andre.homecontrol.core.content.ContentSource;
import dev.andre.homecontrol.core.content.ContentSources;

import java.util.List;

/** One cached rail as the dashboard shows it: never a token, never the raw content model. */
public record RailView(String key, String domId, String sourceId, String railId, String title, String sourceName,
                       String status, List<ContentItemView> items, String error, boolean refreshing, long version) {

    public static RailView of(RailSnapshot snapshot, ContentSources sources) {
        String sourceName = sources.find(snapshot.sourceId()).map(ContentSource::displayName).orElse(snapshot.sourceId());
        return new RailView(snapshot.key(), "rail-" + snapshot.key().replaceAll("[^A-Za-z0-9-]", "-"),
                snapshot.sourceId(), snapshot.railId(), snapshot.descriptor().title(), sourceName,
                snapshot.status().name(), snapshot.items().stream().map(ContentItemView::of).toList(),
                snapshot.error(), snapshot.refreshing(), snapshot.version());
    }
}

package dev.andre.homecontrol.core.content;

import dev.andre.homecontrol.core.playback.ContentItem;

import java.time.Instant;
import java.util.List;

/** One loaded rail. {@code fetchedAt} lets the rail cache (D1) decide staleness. */
public record Rail(RailDescriptor descriptor, List<ContentItem> items, Instant fetchedAt) {
    public Rail {
        items = items == null ? List.of() : List.copyOf(items);
    }
}

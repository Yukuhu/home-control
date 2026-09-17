package dev.andre.homecontrol.content;

import dev.andre.homecontrol.core.content.RailDescriptor;
import dev.andre.homecontrol.core.playback.ContentItem;

import java.time.Instant;
import java.util.List;

/** One cached rail as the dashboard shows it. {@code items} are the last good items, even when FAILED. */
public record RailSnapshot(RailDescriptor descriptor, RailStatus status, List<ContentItem> items,
                           Instant fetchedAt, String error, boolean refreshing, long version) {

    public RailSnapshot {
        items = items == null ? List.of() : List.copyOf(items);
    }

    static RailSnapshot loading(RailDescriptor descriptor, long version) {
        return new RailSnapshot(descriptor, RailStatus.LOADING, List.of(), null, null, false, version);
    }

    public String sourceId() {
        return descriptor.sourceId();
    }

    public String railId() {
        return descriptor.id();
    }

    public String key() {
        return key(descriptor);
    }

    public static String key(RailDescriptor descriptor) {
        return descriptor.sourceId() + "/" + descriptor.id();
    }

    public boolean hasItems() {
        return !items.isEmpty();
    }

    RailSnapshot refreshing(long newVersion) {
        return new RailSnapshot(descriptor, status, items, fetchedAt, error, true, newVersion);
    }

    RailSnapshot ready(List<ContentItem> newItems, Instant newFetchedAt, long newVersion) {
        return new RailSnapshot(descriptor, RailStatus.READY, newItems, newFetchedAt, null, false, newVersion);
    }

    RailSnapshot failed(String message, long newVersion) {
        return new RailSnapshot(descriptor, RailStatus.FAILED, items, fetchedAt, message, false, newVersion);
    }
}

package dev.andre.homecontrol.core.content;

import dev.andre.homecontrol.core.playback.ContentItem;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * A content source (spec §5.2, §7). Only sources speak content APIs. Items carry every playable
 * reference the source can build without secrets; token-bearing references are made at play time.
 */
public interface ContentSource {

    /** Stable key, e.g. {@code jellyfin}; also the {@code source} request parameter. */
    String id();

    String displayName();

    /** Configured and usable right now (no I/O). */
    default boolean available() {
        return true;
    }

    /** The rails this source offers now, in its default order. No I/O; empty while unavailable. */
    List<RailDescriptor> rails();

    /** Loads one rail. I/O. Unknown id → IllegalArgumentException; upstream failure → ContentSourceException. */
    Rail rail(String railId);

    /** Re-reads one item by the id it had in a rail or search result. I/O. Empty when it no longer exists. */
    Optional<ContentItem> item(String itemId);

    default boolean searchable() {
        return false;
    }

    /** I/O. Only called when {@link #searchable()} and {@link #available()}. */
    default List<ContentItem> search(String query, int limit) {
        throw new UnsupportedOperationException(displayName() + " cannot search");
    }

    /**
     * True when each search costs a scarce budget (e.g. YouTube's 100 quota units). Such sources are left out of
     * the as-you-type search and searched only when the user asks for them (spec §6.1 "quota-aware").
     */
    default boolean searchOnDemand() {
        return false;
    }

    /** A short note next to the on-demand search button, e.g. "17 of 20 YouTube searches left today". */
    default Optional<String> searchNote() {
        return Optional.empty();
    }

    /** How long a loaded rail stays fresh. Users override it per source (D4). */
    default Duration defaultRefreshInterval() {
        return Duration.ofMinutes(15);
    }
}

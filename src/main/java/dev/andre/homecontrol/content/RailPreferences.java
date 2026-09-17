package dev.andre.homecontrol.content;

import dev.andre.homecontrol.core.content.ContentSource;
import dev.andre.homecontrol.core.content.RailDescriptor;

import java.time.Duration;
import java.util.List;

/** What the rail cache should keep fresh, and how. Replaced by stored preferences in D4. */
public interface RailPreferences {

    /** The rails to show and keep fresh, in display order; it must not do I/O. */
    List<RailDescriptor> rails(List<ContentSource> sources);

    /** How long a rail loaded from {@code source} stays fresh before the cache refreshes it again. */
    Duration refreshInterval(ContentSource source);

    default boolean sourceEnabled(String sourceId) {
        return true;
    }
}

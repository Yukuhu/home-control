package dev.andre.homecontrol.content;

import java.util.List;

/** Published whenever the ordered set of cached rail keys changes (a source appeared, disappeared or reordered). */
public record RailsChangedEvent(List<String> keys) {
    public RailsChangedEvent {
        keys = List.copyOf(keys);
    }
}

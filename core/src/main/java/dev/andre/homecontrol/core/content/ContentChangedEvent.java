package dev.andre.homecontrol.core.content;

/**
 * Published by a source whose rails changed because of a user action; the rail cache refreshes them now. {@code railId}
 * names the one rail that changed, or is null when any rail of the source may have.
 */
public record ContentChangedEvent(String sourceId, String railId) {

    public ContentChangedEvent(String sourceId) {
        this(sourceId, null);
    }
}

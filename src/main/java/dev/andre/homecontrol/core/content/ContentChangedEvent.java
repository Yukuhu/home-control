package dev.andre.homecontrol.core.content;

/** Published by a source whose rails changed because of a user action; the rail cache refreshes them now. */
public record ContentChangedEvent(String sourceId) {
}

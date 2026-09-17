package dev.andre.homecontrol.content;

/** Published whenever the stored {@code SourcePreferences} change; {@link RailCache} reschedules on it. */
public record SourcePreferencesChangedEvent() {
}

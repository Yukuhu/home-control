package dev.andre.homecontrol.content;

/** Published whenever the stored {@code SourcePreferences} change; {@link RailCache} reschedules on it. */
// A Spring event matched by its type alone: listeners re-read the stored preferences, so it carries nothing.
@SuppressWarnings("java:S2094")
public record SourcePreferencesChangedEvent() {
}

package dev.andre.homecontrol.core;

/**
 * What an app link starts playing, for adapters that see the foreground app but no media:
 * they show {@code title} while {@code appPackage} is in front. {@code durationSeconds} is
 * null for live or unknown media.
 */
public record LaunchedMedia(String appPackage, String title, Double durationSeconds) {
    public LaunchedMedia {
        if (appPackage == null || appPackage.isBlank() || title == null || title.isBlank()) {
            throw new IllegalArgumentException("Launched media needs an app package and a title");
        }
    }
}

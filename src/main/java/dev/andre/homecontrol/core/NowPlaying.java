package dev.andre.homecontrol.core;

/**
 * What a device is playing, as far as its adapter can tell (spec §6.2). The position is as of
 * the owning state's {@code updatedAt}; {@code durationSeconds} is null for live or unknown media.
 */
public record NowPlaying(String title, PlaybackState state, double positionSeconds, Double durationSeconds) {
}

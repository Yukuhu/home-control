package dev.andre.homecontrol.sources.jellyfin;

import java.time.Instant;

/** A Jellyfin client session (an app instance) as the server reports it. */
public record JellyfinSession(String id, String deviceId, String deviceName, String client, String remoteAddress,
                              Instant lastActivity, boolean supportsMediaControl) {
}

package dev.andre.homecontrol.sources.jellyfin;

import java.net.URI;

/** Everything needed for an authenticated call. {@code userId} may be null while resolving an API key's user. */
public record JellyfinConnection(URI serverUrl, String token, String deviceId, String userId) {

    @Override
    public String toString() {
        return "JellyfinConnection[serverUrl=" + serverUrl + ", userId=" + userId + "]";
    }
}

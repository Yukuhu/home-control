package dev.andre.homecontrol.sources.jellyfin;

import dev.andre.homecontrol.core.playback.DelegatedRoute;

/** Jellyfin's routes, run by {@link JellyfinRouteExecutor} and {@link JellyfinVlcExecutor}. */
public sealed interface JellyfinRoute extends DelegatedRoute {

    @Override
    default String source() {
        return "Jellyfin";
    }

    /** Deferred VLC launch; credentials are resolved only when Play is pressed. */
    record Vlc(String itemId) implements JellyfinRoute {
        @Override
        public String key() {
            return "jellyfin-vlc";
        }

        @Override
        public boolean optimistic() {
            return true;
        }

        @Override public String describe() { return "Open in VLC (from beginning; no Jellyfin progress tracking)"; }
    }

    /** Wake an Android TV and open Jellyfin as needed, then play through its fresh session. */
    record App(String itemId, long startPositionTicks) implements JellyfinRoute {
        @Override
        public String key() {
            return "jellyfin-app";
        }

        @Override
        public String describe() {
            return "Play in Jellyfin (wake device and open app if needed)";
        }
    }

    /** Tell a Jellyfin session to play. Android TV also checks power and foreground app at execution time. */
    record Session(String sessionId, String itemId, long startPositionTicks, String client) implements JellyfinRoute {
        @Override
        public String key() {
            return "jellyfin-session";
        }

        @Override
        public String describe() {
            return client == null || client.isBlank()
                    ? "Play in the open Jellyfin app"
                    : "Play in the open Jellyfin app (" + client + ")";
        }
    }
}

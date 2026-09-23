package dev.andre.homecontrol.core.playback;

/** Stable, browser-visible identifiers for routes. Never contains payloads: they may carry tokens. */
public final class RouteKeys {

    private RouteKeys() {
    }

    public static String key(Route route) {
        return switch (route) {
            case Route.OpenAppLink _ -> "app-link";
            case Route.WorkflowCast _ -> "workflow-cast";
            case Route.Cast cast -> "cast:" + cast.receiverAppId();
            case Route.CastMessage message -> "cast-message:" + message.receiverAppId();
            case Route.JellyfinSession _ -> "jellyfin-session";
            case Route.JellyfinVlc _ -> "jellyfin-vlc";
            case Route.JellyfinApp _ -> "jellyfin-app";
            case Route.YouTubeLounge _ -> "youtube-lounge";
            case Route.Render _ -> "render";
            case Route.PlayLocally _ -> "local-audio";
            case Route.Unroutable _ -> "unroutable";
        };
    }

    /** True when success only means "the device accepted it" (spec §5.3: the app may not be installed). */
    public static boolean optimistic(Route route) {
        return route instanceof Route.OpenAppLink || route instanceof Route.JellyfinVlc;
    }
}

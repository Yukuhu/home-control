package dev.andre.homecontrol.core.playback;

/** Stable, browser-visible identifiers for routes. Never contains payloads: they may carry tokens. */
public final class RouteKeys {

    private RouteKeys() {
    }

    public static String key(Route route) {
        return switch (route) {
            case Route.OpenAppLink ignored -> "app-link";
            case Route.Cast cast -> "cast:" + cast.receiverAppId();
            case Route.CastMessage message -> "cast-message:" + message.receiverAppId();
            case Route.JellyfinSession ignored -> "jellyfin-session";
            case Route.YouTubeLounge ignored -> "youtube-lounge";
            case Route.Render ignored -> "render";
            case Route.PlayLocally ignored -> "local-audio";
            case Route.Unroutable ignored -> "unroutable";
        };
    }

    /** True when success only means "the device accepted it" (spec §5.3: the app may not be installed). */
    public static boolean optimistic(Route route) {
        return route instanceof Route.OpenAppLink;
    }
}

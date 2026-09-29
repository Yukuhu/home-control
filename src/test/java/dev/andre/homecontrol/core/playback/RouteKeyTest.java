package dev.andre.homecontrol.core.playback;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The browser sends a route's key back to skip it, so every key is pinned here and never carries a payload. */
class RouteKeyTest {

    private static final Route.OpenAppLink APP_LINK =
            new Route.OpenAppLink(URI.create("https://www.youtube.com/watch?v=abc"), "youtube");
    private static final Route.JellyfinVlc VLC = new Route.JellyfinVlc("item-1");

    /** Every route, each with a payload that must never reach its key. */
    private static Map<Route, String> keys() {
        Map<Route, String> keys = new LinkedHashMap<>();
        keys.put(APP_LINK, "app-link");
        keys.put(new Route.WorkflowCast("workflow-1", 3, "entry"), "workflow-cast");
        keys.put(new Route.Cast("CC1AD845", Map.of("contentId", "http://nas/a.mp4?ApiKey=tok")), "cast:CC1AD845");
        keys.put(new Route.CastMessage("F007D354", "urn:x-cast:com.connectsdk",
                Map.of("command", "PlayNow", "accessToken", "tok"), "the Jellyfin receiver"), "cast-message:F007D354");
        keys.put(new Route.JellyfinSession("s1", "item-1", 600L, "Android TV"), "jellyfin-session");
        keys.put(VLC, "jellyfin-vlc");
        keys.put(new Route.JellyfinApp("item-1", 0L), "jellyfin-app");
        keys.put(new Route.YouTubeLounge("abc"), "youtube-lounge");
        keys.put(new Route.Render(URI.create("http://nas/a.mp3?ApiKey=tok"), "audio/mpeg", "A", null), "render");
        keys.put(new Route.PlayLocally(URI.create("http://nas/a.mp3?ApiKey=tok"), "audio/mpeg", "A", null), "local-audio");
        keys.put(new Route.Unroutable("no route"), "unroutable");
        return keys;
    }

    @Test
    void everyRouteHasItsStableKey() {
        keys().forEach((route, key) -> assertThat(route.key()).as(route.getClass().getSimpleName()).isEqualTo(key));
    }

    @Test
    void noKeyCarriesAPayload() {
        keys().keySet().forEach(route -> assertThat(route.key()).as(route.getClass().getSimpleName())
                .doesNotContain("tok").doesNotContain("?"));
    }

    @Test
    void appLinksAndVlcAreOptimistic() {
        assertThat(APP_LINK.optimistic()).isTrue();
        assertThat(VLC.optimistic()).isTrue();
    }

    @Test
    void everyOtherRouteIsConfirmedByTheDevice() {
        List<Route> confirmed = keys().keySet().stream().filter(route -> route != APP_LINK && route != VLC).toList();

        assertThat(confirmed).hasSize(9)
                .allSatisfy(route -> assertThat(route.optimistic()).as(route.getClass().getSimpleName()).isFalse());
    }
}

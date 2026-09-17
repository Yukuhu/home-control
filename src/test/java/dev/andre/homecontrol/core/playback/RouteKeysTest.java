package dev.andre.homecontrol.core.playback;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RouteKeysTest {

    @Test
    void keysAreStableAndSecretFree() {
        assertThat(RouteKeys.key(new Route.OpenAppLink(URI.create("https://www.youtube.com/watch?v=abc"), "youtube")))
                .isEqualTo("app-link");
        assertThat(RouteKeys.key(new Route.Cast("CC1AD845", Map.of()))).isEqualTo("cast:CC1AD845");
        Route.CastMessage message = new Route.CastMessage("F007D354", "urn:x-cast:com.connectsdk",
                Map.of("command", "PlayNow", "accessToken", "tok"), "the Jellyfin receiver");
        String key = RouteKeys.key(message);
        assertThat(key).isEqualTo("cast-message:F007D354").doesNotContain("tok");
        assertThat(RouteKeys.key(new Route.JellyfinSession("s1", "item-1", 600L, "Android TV")))
                .isEqualTo("jellyfin-session");
        assertThat(RouteKeys.key(new Route.Unroutable("no route"))).isEqualTo("unroutable");
    }

    @Test
    void youtubeLoungeKey() {
        assertThat(RouteKeys.key(new Route.YouTubeLounge("x"))).isEqualTo("youtube-lounge");
        assertThat(RouteKeys.optimistic(new Route.YouTubeLounge("x"))).isFalse();
    }

    @Test
    void localPlaybackHasAKey() {
        Route.PlayLocally local = new Route.PlayLocally(URI.create("http://nas/a.mp3"), "audio/mpeg", "A", null);
        assertThat(RouteKeys.key(local)).isEqualTo("local-audio");
        assertThat(RouteKeys.optimistic(local)).isFalse();
    }

    @Test
    void onlyAppLinksAreOptimistic() {
        assertThat(RouteKeys.optimistic(new Route.OpenAppLink(URI.create("https://x"), "web"))).isTrue();
        assertThat(RouteKeys.optimistic(new Route.Cast("CC1AD845", Map.of()))).isFalse();
        assertThat(RouteKeys.optimistic(new Route.CastMessage("F007D354", "ns", Map.of(), "label"))).isFalse();
        assertThat(RouteKeys.optimistic(new Route.JellyfinSession("s1", "item-1", 0, "c"))).isFalse();
        assertThat(RouteKeys.optimistic(new Route.Unroutable("x"))).isFalse();
    }
}

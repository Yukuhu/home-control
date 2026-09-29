package dev.andre.homecontrol.sources.jellyfin;

import dev.andre.homecontrol.core.Action;
import dev.andre.homecontrol.core.Capability;
import dev.andre.homecontrol.core.playback.ContentItem;
import dev.andre.homecontrol.core.playback.ContentKind;
import dev.andre.homecontrol.core.playback.PlayableRef;
import dev.andre.homecontrol.core.playback.PlaybackPlanner;
import dev.andre.homecontrol.core.playback.Route;
import dev.andre.homecontrol.core.playback.RouteStrategies;
import dev.andre.homecontrol.core.playback.RouteStrategy;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Jellyfin's rung, the first: VLC where the device can take it, else the open app, else starting the app. */
class JellyfinSessionStrategyTest {

    private static final JellyfinPlayable.Session OPEN_APP =
            new JellyfinPlayable.Session("1d2c3b4a59687f6e5d4c3b2a19081726", "item-1", 600L, "Android TV");
    private static final JellyfinPlayable.App START_APP = new JellyfinPlayable.App("item-1", 600L);
    private static final JellyfinPlayable.Vlc VLC = new JellyfinPlayable.Vlc("item-1");
    private static final PlayableRef.StreamUrl STREAM =
            new PlayableRef.StreamUrl(URI.create("http://nas.local/films/bunny.mp4"), "video/mp4");
    private static final PlayableRef.AppLink LINK =
            new PlayableRef.AppLink(URI.create("https://www.youtube.com/watch?v=abc"), "youtube");
    private static final PlayableRef.CastMessage JELLYFIN_MESSAGE = new PlayableRef.CastMessage(new Action.CastMessage(
            "F007D354", "urn:x-cast:com.connectsdk", Map.of("command", "PlayNow", "accessToken", "tok-1")), "the Jellyfin receiver");

    private final PlaybackPlanner planner = new PlaybackPlanner(withJellyfin());

    private static List<RouteStrategy> withJellyfin() {
        List<RouteStrategy> strategies = new ArrayList<>(RouteStrategies.core());
        strategies.add(new JellyfinSessionStrategy());
        return strategies;
    }

    private static ContentItem item(PlayableRef... playables) {
        return new ContentItem("x", "test", ContentKind.VIDEO, "Title", null, null, List.of(playables));
    }

    @Test
    void anUnresolvedJellyfinItemMeansTheSourceIsSwitchedOff() {
        assertThat(planner.plan(item(new JellyfinPlayable.Item("srv", "item-1", 0)), EnumSet.allOf(Capability.class)).first())
                .isEqualTo(new Route.Unroutable("Jellyfin is switched off on this server"));
    }


    @Test
    void anOpenJellyfinAppComesFirst() {
        Route route = planner.plan(item(LINK, JELLYFIN_MESSAGE, STREAM, OPEN_APP),
                EnumSet.of(Capability.JELLYFIN_CLIENT, Capability.APP_LINK, Capability.CAST_RECEIVER)).first();

        assertThat(route).isEqualTo(new JellyfinRoute.Session("1d2c3b4a59687f6e5d4c3b2a19081726", "item-1", 600L, "Android TV"));
        assertThat(route.describe()).isEqualTo("Play in the open Jellyfin app (Android TV)");
        assertThat(new JellyfinRoute.Session("s", "i", 0, " ").describe()).isEqualTo("Play in the open Jellyfin app");
    }


    @Test
    void aSessionReferenceWithoutTheLiveCapabilityDoesNotRoute() {
        assertThat(planner.plan(item(OPEN_APP), EnumSet.of(Capability.APP_LINK)).first())
                .isEqualTo(new Route.Unroutable("the open Jellyfin app cannot be controlled"));
    }


    @Test
    void listsEveryApplicableRouteInSpecOrder() {
        List<Route> routes = planner.plan(item(STREAM, JELLYFIN_MESSAGE, LINK, OPEN_APP),
                EnumSet.of(Capability.JELLYFIN_CLIENT, Capability.APP_LINK, Capability.CAST_RECEIVER)).routes();

        assertThat(routes).extracting(Route::key)
                .containsExactly("jellyfin-session", "app-link", "cast-message:F007D354", "cast:CC1AD845");
        assertThat(planner.plan(item(STREAM, LINK), EnumSet.of(Capability.APP_LINK, Capability.CAST_RECEIVER)).first())
                .isEqualTo(routes.get(1));
    }


    @Test
    void vlcWinsOnADeviceThatOpensAppLinksAndTakesKeys() {
        assertThat(planner.plan(item(OPEN_APP, VLC),
                EnumSet.of(Capability.JELLYFIN_CLIENT, Capability.APP_LINK, Capability.REMOTE_KEYS)).first())
                .isEqualTo(new JellyfinRoute.Vlc("item-1"));
    }

    @Test
    void withoutAppLinksAndKeysVlcGivesWayToTheOpenAppThenToStartingIt() {
        assertThat(planner.plan(item(VLC, OPEN_APP), EnumSet.of(Capability.JELLYFIN_CLIENT, Capability.APP_LINK)).first())
                .isEqualTo(new JellyfinRoute.Session("1d2c3b4a59687f6e5d4c3b2a19081726", "item-1", 600L, "Android TV"));
        assertThat(planner.plan(item(VLC, START_APP), EnumSet.of(Capability.JELLYFIN_CLIENT, Capability.REMOTE_KEYS)).first())
                .isEqualTo(new JellyfinRoute.App("item-1", 600L));
    }
}

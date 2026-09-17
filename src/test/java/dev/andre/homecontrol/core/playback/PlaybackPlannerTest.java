package dev.andre.homecontrol.core.playback;

import dev.andre.homecontrol.core.Capability;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PlaybackPlannerTest {

    private final PlaybackPlanner planner = new PlaybackPlanner(
            List.of(new AppLinkStrategy(), new CastMessageStrategy(), new CastLoadStrategy(), new CastStreamStrategy()));

    private static final PlayableRef.CastLoad JELLYFIN_LOAD =
            new PlayableRef.CastLoad("F007D354", Map.of("media", Map.of("contentId", "item-1")));
    private static final PlayableRef.StreamUrl STREAM =
            new PlayableRef.StreamUrl(URI.create("http://nas.local/films/bunny.mp4"), "video/mp4");
    private static final PlayableRef.AppLink LINK =
            new PlayableRef.AppLink(URI.create("https://www.youtube.com/watch?v=abc"), "youtube");
    private static final PlayableRef.CastMessage JELLYFIN_MESSAGE = new PlayableRef.CastMessage(
            "F007D354", "urn:x-cast:com.connectsdk", Map.of("command", "PlayNow", "accessToken", "tok-1"), "the Jellyfin receiver");

    private static ContentItem item(PlayableRef... playables) {
        return new ContentItem("x", "test", ContentKind.VIDEO, "Title", null, null, List.of(playables));
    }

    @Test
    void routesAnAppLinkToADeviceThatCanOpenAppLinks() {
        URI uri = URI.create("https://www.youtube.com/watch?v=abc");

        Route route = planner.plan(item(new PlayableRef.AppLink(uri, "youtube")),
                EnumSet.of(Capability.REMOTE_KEYS, Capability.APP_LINK));

        assertThat(route).isEqualTo(new Route.OpenAppLink(uri, "youtube"));
        assertThat(route.describe()).isEqualTo("Open in the YouTube app");
    }

    @Test
    void explainsWhyAnAppLinkCannotReachADeviceWithoutTheCapability() {
        Route route = planner.plan(item(new PlayableRef.AppLink(URI.create("https://x"), "web")),
                EnumSet.of(Capability.MEDIA_RENDERER));

        assertThat(route).isInstanceOfSatisfying(Route.Unroutable.class,
                unroutable -> assertThat(unroutable.reason()).contains("cannot open app links"));
    }

    @Test
    void explainsThatJellyfinItemsHaveNoRouteYet() {
        Route route = planner.plan(item(new PlayableRef.JellyfinItem("srv", "item", 0)), EnumSet.allOf(Capability.class));

        assertThat(route).isInstanceOfSatisfying(Route.Unroutable.class, unroutable ->
                assertThat(unroutable.reason()).contains("Jellyfin").contains("not supported yet"));
    }

    @Test
    void castsACastLoadToACastReceiver() {
        Route route = planner.plan(item(JELLYFIN_LOAD), EnumSet.of(Capability.CAST_RECEIVER, Capability.VOLUME));

        assertThat(route).isEqualTo(new Route.Cast("F007D354", JELLYFIN_LOAD.payload()));
        assertThat(route.describe()).isEqualTo("Cast with the Jellyfin receiver");
        assertThat(((Route.Cast) route).action()).isEqualTo(new dev.andre.homecontrol.core.Action.CastLoad("F007D354", JELLYFIN_LOAD.payload()));
    }

    @Test
    void castsACustomMessageBeforeACastLoadOrAStream() {
        Route route = planner.plan(item(STREAM, JELLYFIN_LOAD, JELLYFIN_MESSAGE), EnumSet.of(Capability.CAST_RECEIVER));

        assertThat(route).isEqualTo(new Route.CastMessage("F007D354", "urn:x-cast:com.connectsdk",
                JELLYFIN_MESSAGE.message(), "the Jellyfin receiver"));
        assertThat(route.describe()).isEqualTo("Cast with the Jellyfin receiver");
        assertThat(((Route.CastMessage) route).action()).isEqualTo(new dev.andre.homecontrol.core.Action.CastMessage("F007D354",
                "urn:x-cast:com.connectsdk", JELLYFIN_MESSAGE.message()));
        assertThat(route.toString()).doesNotContain("tok-1");
        assertThat(JELLYFIN_MESSAGE.toString()).doesNotContain("tok-1");
    }

    @Test
    void aCustomMessageNeedsACastReceiver() {
        assertThat(planner.plan(item(JELLYFIN_MESSAGE), EnumSet.of(Capability.APP_LINK)))
                .isInstanceOfSatisfying(Route.Unroutable.class, u -> assertThat(u.reason()).contains("this device is not a Cast receiver"));
    }

    @Test
    void castsAStreamThroughTheDefaultMediaReceiverWithTheItemTitle() {
        Route route = planner.plan(item(STREAM), EnumSet.of(Capability.CAST_RECEIVER));

        assertThat(route).isEqualTo(new Route.Cast("CC1AD845", CastLoads.defaultMediaReceiver(STREAM, "Title")));
        assertThat(route.describe()).isEqualTo("Cast with the Default Media Receiver");
        assertThat(new Route.Cast("ABCD1234", Map.of()).describe()).isEqualTo("Cast with receiver app ABCD1234");
    }

    @Test
    void followsSpecOrderAppLinkThenCastLoadThenStream() {
        ContentItem everything = item(STREAM, JELLYFIN_LOAD, LINK);

        assertThat(planner.plan(everything, EnumSet.of(Capability.APP_LINK, Capability.CAST_RECEIVER)))
                .isEqualTo(new Route.OpenAppLink(LINK.uri(), "youtube"));
        assertThat(planner.plan(everything, EnumSet.of(Capability.CAST_RECEIVER)))
                .isEqualTo(new Route.Cast("F007D354", JELLYFIN_LOAD.payload()));
        assertThat(planner.plan(item(STREAM, LINK), EnumSet.of(Capability.CAST_RECEIVER)))
                .isInstanceOfSatisfying(Route.Cast.class, cast -> assertThat(cast.receiverAppId()).isEqualTo("CC1AD845"));
    }

    @Test
    void explainsWhyCastAndStreamsCannotReachADeviceWithoutCast() {
        Route route = planner.plan(item(JELLYFIN_LOAD, STREAM), EnumSet.of(Capability.REMOTE_KEYS, Capability.APP_LINK));

        assertThat(route).isInstanceOfSatisfying(Route.Unroutable.class, unroutable -> assertThat(unroutable.reason())
                .contains("this device is not a Cast receiver")
                .contains("this device cannot play a direct stream"));
    }

    @Test
    void theApplicationsPlannerUsesTheSpecOrder() {
        PlaybackPlanner configured = new dev.andre.homecontrol.HomeControlConfiguration().playbackPlanner();
        ContentItem everything = item(STREAM, JELLYFIN_LOAD, LINK);

        assertThat(configured.plan(everything, EnumSet.of(Capability.APP_LINK, Capability.CAST_RECEIVER)))
                .isInstanceOf(Route.OpenAppLink.class);
        assertThat(configured.plan(everything, EnumSet.of(Capability.CAST_RECEIVER)))
                .isEqualTo(new Route.Cast("F007D354", JELLYFIN_LOAD.payload()));
        assertThat(configured.plan(item(JELLYFIN_LOAD, JELLYFIN_MESSAGE), EnumSet.of(Capability.CAST_RECEIVER)))
                .isInstanceOf(Route.CastMessage.class);
    }

    @Test
    void anItemWithNothingPlayableIsUnroutable() {
        Route route = planner.plan(item(), Set.of(Capability.APP_LINK));

        assertThat(route).isEqualTo(new Route.Unroutable("This item has nothing playable"));
    }

    @Test
    void theFirstStrategyThatRoutesWins() {
        URI firstUri = URI.create("https://www.netflix.com/title/1");
        URI secondUri = URI.create("https://www.dazn.com/title/2");
        RouteStrategy first = (i, caps) -> Optional.of(new Route.OpenAppLink(firstUri, "netflix"));
        RouteStrategy second = (i, caps) -> Optional.of(new Route.OpenAppLink(secondUri, "dazn"));
        PlaybackPlanner ordered = new PlaybackPlanner(List.of(first, second));

        assertThat(ordered.plan(item(new PlayableRef.AppLink(firstUri, "netflix")), Set.of(Capability.APP_LINK)))
                .isEqualTo(new Route.OpenAppLink(firstUri, "netflix"));
    }

    @Test
    void fallsBackToAGenericReasonWhenNoSpecificOneApplies() {
        PlaybackPlanner none = new PlaybackPlanner(List.of());

        assertThat(none.plan(item(new PlayableRef.AppLink(URI.create("https://x"), "web")), Set.of(Capability.APP_LINK)))
                .isEqualTo(new Route.Unroutable("no route to this device"));
    }
}

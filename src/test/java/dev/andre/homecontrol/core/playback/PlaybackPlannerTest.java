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

    private final PlaybackPlanner planner = new PlaybackPlanner(List.of(new AppLinkStrategy()));

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
    void explainsThatOtherReferenceKindsHaveNoRouteYet() {
        Route route = planner.plan(item(
                        new PlayableRef.CastLoad("CC1AD845", Map.of()),
                        new PlayableRef.JellyfinItem("srv", "item", 0),
                        new PlayableRef.StreamUrl(URI.create("http://nas/a.mp4"), "video/mp4")),
                EnumSet.allOf(Capability.class));

        assertThat(route).isInstanceOfSatisfying(Route.Unroutable.class, unroutable ->
                assertThat(unroutable.reason())
                        .contains("cast").contains("Jellyfin").contains("stream")
                        .contains("not supported yet"));
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

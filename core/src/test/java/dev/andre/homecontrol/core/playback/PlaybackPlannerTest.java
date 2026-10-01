package dev.andre.homecontrol.core.playback;

import dev.andre.homecontrol.core.Action;
import dev.andre.homecontrol.core.Capability;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PlaybackPlannerTest {

    private final PlaybackPlanner planner = new PlaybackPlanner(RouteStrategies.core());

    private static final PlayableRef.CastLoad JELLYFIN_LOAD =
            new PlayableRef.CastLoad("F007D354", Map.of("media", Map.of("contentId", "item-1")));
    private static final PlayableRef.StreamUrl STREAM =
            new PlayableRef.StreamUrl(URI.create("http://nas.local/films/bunny.mp4"), "video/mp4");
    private static final PlayableRef.StreamUrl STREAM_WITH_KEY =
            new PlayableRef.StreamUrl(URI.create("http://nas.local/films/bunny.mp4?ApiKey=tok-2"), "video/mp4");
    private static final PlayableRef.StreamUrl AUDIO =
            new PlayableRef.StreamUrl(URI.create("http://nas/a.mp3"), "audio/mpeg");
    private static final PlayableRef.StreamUrl VIDEO =
            new PlayableRef.StreamUrl(URI.create("http://nas/f.mp4"), "video/mp4");
    private static final PlayableRef.AppLink LINK =
            new PlayableRef.AppLink(URI.create("https://www.youtube.com/watch?v=abc"), "youtube");
    private static final PlayableRef.CastMessage JELLYFIN_MESSAGE = new PlayableRef.CastMessage(new Action.CastMessage(
            "F007D354", "urn:x-cast:com.connectsdk", Map.of("command", "PlayNow", "accessToken", "tok-1")), "the Jellyfin receiver");

    private static ContentItem item(PlayableRef... playables) {
        return new ContentItem("x", "test", ContentKind.VIDEO, "Title", null, null, List.of(playables));
    }

    @Test
    void routesAnAppLinkToADeviceThatCanOpenAppLinks() {
        URI uri = URI.create("https://www.youtube.com/watch?v=abc");

        Route route = planner.plan(item(new PlayableRef.AppLink(uri, "youtube")),
                EnumSet.of(Capability.REMOTE_KEYS, Capability.APP_LINK)).first();

        assertThat(route).isEqualTo(new Route.OpenAppLink(uri, "youtube"));
        assertThat(route.describe()).isEqualTo("Open in the YouTube app");
    }

    @Test
    void explainsWhyAnAppLinkCannotReachADeviceWithoutTheCapability() {
        Route route = planner.plan(item(new PlayableRef.AppLink(URI.create("https://x"), "web")),
                EnumSet.of(Capability.MEDIA_RENDERER)).first();

        assertThat(route).isInstanceOfSatisfying(Route.Unroutable.class,
                unroutable -> assertThat(unroutable.reason()).contains("cannot open app links"));
    }

    @Test
    void jellyfinFallsBackFromSessionToReceiverToStream() {
        ContentItem resolved = item(JELLYFIN_MESSAGE, STREAM);

        assertThat(planner.plan(resolved, EnumSet.of(Capability.CAST_RECEIVER)).first()).isInstanceOf(Route.CastMessage.class);
        assertThat(planner.plan(item(STREAM), EnumSet.of(Capability.CAST_RECEIVER)).first())
                .isInstanceOfSatisfying(Route.Cast.class, cast -> assertThat(cast.receiverAppId()).isEqualTo("CC1AD845"));
    }

    @Test
    void castsACastLoadToACastReceiver() {
        Route route = planner.plan(item(JELLYFIN_LOAD), EnumSet.of(Capability.CAST_RECEIVER, Capability.VOLUME)).first();

        assertThat(route).isEqualTo(new Route.Cast("F007D354", JELLYFIN_LOAD.payload()));
        assertThat(route.describe()).isEqualTo("Cast with the Jellyfin receiver");
        assertThat(((Route.Cast) route).action()).isEqualTo(new dev.andre.homecontrol.core.Action.CastLoad("F007D354", JELLYFIN_LOAD.payload()));
    }

    @Test
    void castsACustomMessageBeforeACastLoadOrAStream() {
        Route route = planner.plan(item(STREAM, JELLYFIN_LOAD, JELLYFIN_MESSAGE), EnumSet.of(Capability.CAST_RECEIVER)).first();

        assertThat(route).isEqualTo(new Route.CastMessage(new Action.CastMessage("F007D354", "urn:x-cast:com.connectsdk",
                JELLYFIN_MESSAGE.message().message()), "the Jellyfin receiver"));
        assertThat(route.describe()).isEqualTo("Cast with the Jellyfin receiver");
        assertThat(((Route.CastMessage) route).action()).isEqualTo(new Action.CastMessage("F007D354",
                "urn:x-cast:com.connectsdk", JELLYFIN_MESSAGE.message().message()));
        assertThat(route.toString()).doesNotContain("tok-1");
        assertThat(JELLYFIN_MESSAGE.toString()).doesNotContain("tok-1");
    }

    @Test
    void aCustomMessageNeedsACastReceiver() {
        assertThat(planner.plan(item(JELLYFIN_MESSAGE), EnumSet.of(Capability.APP_LINK)).first())
                .isInstanceOfSatisfying(Route.Unroutable.class, u -> assertThat(u.reason()).contains("this device is not a Cast receiver"));
    }

    @Test
    void castsAStreamThroughTheDefaultMediaReceiverWithTheItemTitle() {
        Route route = planner.plan(item(STREAM), EnumSet.of(Capability.CAST_RECEIVER)).first();

        assertThat(route).isEqualTo(new Route.Cast("CC1AD845", CastLoads.defaultMediaReceiver(STREAM, "Title")));
        assertThat(route.describe()).isEqualTo("Cast with the Default Media Receiver");
        assertThat(new Route.Cast("ABCD1234", Map.of()).describe()).isEqualTo("Cast with receiver app ABCD1234");
    }

    @Test
    void aCastRouteNeverPrintsTheStreamUrlItLoads() {
        Route route = planner.plan(item(STREAM_WITH_KEY), EnumSet.of(Capability.CAST_RECEIVER)).first();

        assertThat(route).isInstanceOf(Route.Cast.class);
        assertThat(route.toString()).isEqualTo("Cast[receiverAppId=CC1AD845]").doesNotContain("tok-2");
        assertThat(((Route.Cast) route).action().toString()).isEqualTo("CastLoad[receiverAppId=CC1AD845]").doesNotContain("tok-2");
    }

    @Test
    void followsSpecOrderAppLinkThenCastLoadThenStream() {
        ContentItem everything = item(STREAM, JELLYFIN_LOAD, LINK);

        assertThat(planner.plan(everything, EnumSet.of(Capability.APP_LINK, Capability.CAST_RECEIVER)).first())
                .isEqualTo(new Route.OpenAppLink(LINK.uri(), "youtube"));
        assertThat(planner.plan(everything, EnumSet.of(Capability.CAST_RECEIVER)).first())
                .isEqualTo(new Route.Cast("F007D354", JELLYFIN_LOAD.payload()));
        assertThat(planner.plan(item(STREAM, LINK), EnumSet.of(Capability.CAST_RECEIVER)).first())
                .isInstanceOfSatisfying(Route.Cast.class, cast -> assertThat(cast.receiverAppId()).isEqualTo("CC1AD845"));
    }

    @Test
    void explainsWhyCastAndStreamsCannotReachADeviceWithoutCast() {
        Route route = planner.plan(item(JELLYFIN_LOAD, STREAM), EnumSet.of(Capability.REMOTE_KEYS, Capability.APP_LINK)).first();

        assertThat(route).isInstanceOfSatisfying(Route.Unroutable.class, unroutable -> assertThat(unroutable.reason())
                .contains("this device is not a Cast receiver")
                .contains("this device cannot play a direct stream"));
    }

    @Test
    void aMediaRendererGetsTheStream() {
        assertThat(planner.plan(item(STREAM), EnumSet.of(Capability.MEDIA_RENDERER, Capability.VOLUME)).first())
                .isInstanceOfSatisfying(Route.Render.class, render -> assertThat(render.url()).isEqualTo(STREAM.url()));
    }

    @Test
    void castComesBeforeTheMediaRenderer() {
        assertThat(planner.plan(item(STREAM), EnumSet.of(Capability.CAST_RECEIVER, Capability.MEDIA_RENDERER)).first())
                .isInstanceOfSatisfying(Route.Cast.class, cast -> assertThat(cast.receiverAppId()).isEqualTo("CC1AD845"));
        assertThat(planner.plan(item(STREAM, LINK), EnumSet.of(Capability.APP_LINK, Capability.MEDIA_RENDERER)).first())
                .isInstanceOf(Route.OpenAppLink.class);
    }

    @Test
    void explainsThatAStreamWasNotAcceptedByARenderer() {
        PlaybackPlanner withoutRenderers = new PlaybackPlanner(List.of(RouteStrategies.appLink(), RouteStrategies.castStream()));

        assertThat(withoutRenderers.plan(item(STREAM), EnumSet.of(Capability.MEDIA_RENDERER)).first())
                .isInstanceOfSatisfying(Route.Unroutable.class,
                        unroutable -> assertThat(unroutable.reason()).contains("the stream was not accepted"));
        assertThat(withoutRenderers.plan(item(STREAM), EnumSet.of(Capability.REMOTE_KEYS)).first())
                .isInstanceOfSatisfying(Route.Unroutable.class,
                        unroutable -> assertThat(unroutable.reason()).contains("this device cannot play a direct stream"));
    }

    @Test
    void aLocalAudioSinkGetsTheAudioStream() {
        assertThat(planner.plan(item(AUDIO), EnumSet.of(Capability.LOCAL_AUDIO_SINK, Capability.VOLUME)).first())
                .isInstanceOfSatisfying(Route.PlayLocally.class, local -> assertThat(local.url()).isEqualTo(AUDIO.url()));
    }

    @Test
    void everyOtherRungComesFirst() {
        assertThat(planner.plan(item(STREAM), EnumSet.of(Capability.MEDIA_RENDERER, Capability.LOCAL_AUDIO_SINK)).first())
                .isInstanceOf(Route.Render.class);
        assertThat(planner.plan(item(STREAM), EnumSet.of(Capability.CAST_RECEIVER, Capability.LOCAL_AUDIO_SINK)).first())
                .isInstanceOf(Route.Cast.class);
        assertThat(planner.plan(item(AUDIO), EnumSet.of(Capability.MEDIA_RENDERER, Capability.LOCAL_AUDIO_SINK)).routes())
                .extracting(Route::key).containsExactly("render", "local-audio");
    }

    @Test
    void explainsVideoOnABluetoothSpeaker() {
        assertThat(planner.plan(item(VIDEO), EnumSet.of(Capability.LOCAL_AUDIO_SINK, Capability.VOLUME)).first())
                .isInstanceOfSatisfying(Route.Unroutable.class,
                        unroutable -> assertThat(unroutable.reason()).contains("a Bluetooth speaker plays audio streams only"));

        PlaybackPlanner withoutLocalAudio = new PlaybackPlanner(List.of(RouteStrategies.appLink(), RouteStrategies.castStream()));
        assertThat(withoutLocalAudio.plan(item(AUDIO), EnumSet.of(Capability.LOCAL_AUDIO_SINK)).first())
                .isInstanceOfSatisfying(Route.Unroutable.class,
                        unroutable -> assertThat(unroutable.reason()).contains("the stream was not accepted"));
        assertThat(withoutLocalAudio.plan(item(AUDIO), EnumSet.of(Capability.REMOTE_KEYS)).first())
                .isInstanceOfSatisfying(Route.Unroutable.class,
                        unroutable -> assertThat(unroutable.reason()).contains("this device cannot play a direct stream"));
    }

    @Test
    void noApplicableRouteIsAnEmptyListNotUnroutable() {
        assertThat(planner.plan(item(LINK), EnumSet.of(Capability.MEDIA_RENDERER)).routes()).isEmpty();
        assertThat(planner.plan(item(), EnumSet.allOf(Capability.class)).routes()).isEmpty();
    }

    @Test
    void anItemWithNothingPlayableIsUnroutable() {
        Route route = planner.plan(item(), Set.of(Capability.APP_LINK)).first();

        assertThat(route).isEqualTo(new Route.Unroutable("This item has nothing playable"));
    }

    @Test
    void withinARungTheFirstStrategyThatRoutesWins() {
        URI firstUri = URI.create("https://www.netflix.com/title/1");
        URI secondUri = URI.create("https://www.dazn.com/title/2");
        RouteStrategy first = RefStrategy.of(Rung.APP_LINK, Capability.APP_LINK, PlayableRef.AppLink.class,
                (link, item) -> new Route.OpenAppLink(firstUri, "netflix"));
        RouteStrategy second = RefStrategy.of(Rung.APP_LINK, Capability.APP_LINK, PlayableRef.AppLink.class,
                (link, item) -> new Route.OpenAppLink(secondUri, "dazn"));
        PlaybackPlanner ordered = new PlaybackPlanner(List.of(first, second));

        assertThat(ordered.plan(item(new PlayableRef.AppLink(firstUri, "netflix")), Set.of(Capability.APP_LINK)).first())
                .isEqualTo(new Route.OpenAppLink(firstUri, "netflix"));
    }

    @Test
    void fallsBackToAGenericReasonWhenNoSpecificOneApplies() {
        PlaybackPlanner none = new PlaybackPlanner(List.of());

        assertThat(none.plan(item(new PlayableRef.AppLink(URI.create("https://x"), "web")), Set.of(Capability.APP_LINK)).first())
                .isEqualTo(new Route.Unroutable("no route to this device"));
    }

    @Test
    void serviceLinksRouteByCapabilityNotBrand() {
        PlayableRef.AppLink netflixTitle = ServiceLinks.appLink(URI.create("https://www.netflix.com/de/title/80057281?s=a"));

        Route routable = planner.plan(item(netflixTitle), EnumSet.of(Capability.APP_LINK)).first();
        assertThat(routable).isEqualTo(new Route.OpenAppLink(netflixTitle.uri(), netflixTitle.service()));

        Route unroutable = planner.plan(item(netflixTitle),
                EnumSet.of(Capability.CAST_RECEIVER, Capability.MEDIA_RENDERER, Capability.REMOTE_KEYS)).first();
        assertThat(unroutable).isInstanceOfSatisfying(Route.Unroutable.class,
                r -> assertThat(r.reason()).contains("cannot open app links"));

        PlayableRef.AppLink primeHome = ServiceLinks.appLink(ServiceLinks.appHome("primevideo").orElseThrow());

        Route primeRoutable = planner.plan(item(primeHome), EnumSet.of(Capability.APP_LINK)).first();
        assertThat(primeRoutable).isEqualTo(new Route.OpenAppLink(primeHome.uri(), primeHome.service()));

        Route primeUnroutable = planner.plan(item(primeHome),
                EnumSet.of(Capability.CAST_RECEIVER, Capability.MEDIA_RENDERER, Capability.REMOTE_KEYS)).first();
        assertThat(primeUnroutable).isInstanceOfSatisfying(Route.Unroutable.class,
                r -> assertThat(r.reason()).contains("cannot open app links"));
    }
}

package dev.andre.homecontrol.playback;

import dev.andre.homecontrol.core.Action;
import dev.andre.homecontrol.core.Capability;
import dev.andre.homecontrol.core.playback.ContentItem;
import dev.andre.homecontrol.core.playback.ContentKind;
import dev.andre.homecontrol.core.playback.PlayableRef;
import dev.andre.homecontrol.core.playback.PlaybackPlanner;
import dev.andre.homecontrol.core.playback.Route;
import dev.andre.homecontrol.core.playback.RouteStrategy;
import dev.andre.homecontrol.sources.jellyfin.JellyfinPlayable;
import dev.andre.homecontrol.sources.jellyfin.JellyfinRoute;
import dev.andre.homecontrol.testsupport.Planners;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/** The application's planner: every module's strategies on one ladder, whatever order they arrive in (review focus 2). */
class PlaybackPlannerLadderTest {

    private static final JellyfinPlayable.Session OPEN_APP =
            new JellyfinPlayable.Session("1d2c3b4a59687f6e5d4c3b2a19081726", "item-1", 600L, "Android TV");
    private static final PlayableRef.CastLoad JELLYFIN_LOAD =
            new PlayableRef.CastLoad("F007D354", Map.of("media", Map.of("contentId", "item-1")));
    private static final PlayableRef.StreamUrl STREAM =
            new PlayableRef.StreamUrl(URI.create("http://nas.local/films/bunny.mp4"), "video/mp4");
    private static final PlayableRef.StreamUrl AUDIO =
            new PlayableRef.StreamUrl(URI.create("http://nas/a.mp3"), "audio/mpeg");
    private static final PlayableRef.AppLink LINK =
            new PlayableRef.AppLink(URI.create("https://www.youtube.com/watch?v=abc"), "youtube");
    private static final PlayableRef.CastMessage JELLYFIN_MESSAGE = new PlayableRef.CastMessage(new Action.CastMessage(
            "F007D354", "urn:x-cast:com.connectsdk", Map.of("command", "PlayNow", "accessToken", "tok-1")), "the Jellyfin receiver");

    private static ContentItem item(PlayableRef... playables) {
        return new ContentItem("x", "test", ContentKind.VIDEO, "Title", null, null, List.of(playables));
    }

    @Test
    void anyOrderOfStrategiesPlansTheSame() {
        List<RouteStrategy> strategies = Planners.strategies();
        List<RouteStrategy> shuffled = new ArrayList<>(strategies);
        Collections.shuffle(shuffled, new Random(3));

        assertThat(keys(strategies)).containsExactlyElementsOf(Planners.LADDER_KEYS);
        assertThat(keys(strategies.reversed())).containsExactlyElementsOf(Planners.LADDER_KEYS);
        assertThat(keys(shuffled)).containsExactlyElementsOf(Planners.LADDER_KEYS);
    }

    @Test
    void theApplicationsPlannerUsesTheSpecOrder() {
        PlaybackPlanner configured = Planners.production();
        ContentItem everything = item(STREAM, JELLYFIN_LOAD, LINK);

        assertThat(configured.plan(everything, EnumSet.of(Capability.APP_LINK, Capability.CAST_RECEIVER)).first())
                .isInstanceOf(Route.OpenAppLink.class);
        assertThat(configured.plan(everything, EnumSet.of(Capability.CAST_RECEIVER)).first())
                .isEqualTo(new Route.Cast("F007D354", JELLYFIN_LOAD.payload()));
        assertThat(configured.plan(item(JELLYFIN_LOAD, JELLYFIN_MESSAGE), EnumSet.of(Capability.CAST_RECEIVER)).first())
                .isInstanceOf(Route.CastMessage.class);
        assertThat(configured.plan(item(LINK, OPEN_APP), EnumSet.of(Capability.JELLYFIN_CLIENT, Capability.APP_LINK)).first())
                .isInstanceOf(JellyfinRoute.Session.class);
    }


    @Test
    void theApplicationsPlannerEndsWithTheMediaRenderer() {
        PlaybackPlanner configured = Planners.production();

        assertThat(configured.plan(item(STREAM), EnumSet.of(Capability.MEDIA_RENDERER)).first()).isInstanceOf(Route.Render.class);
        assertThat(configured.plan(item(STREAM), EnumSet.of(Capability.CAST_RECEIVER, Capability.MEDIA_RENDERER)).first())
                .isInstanceOf(Route.Cast.class);
        assertThat(configured.plan(item(STREAM), EnumSet.of(Capability.CAST_RECEIVER, Capability.MEDIA_RENDERER)).routes())
                .extracting(Route::key).containsExactly("cast:CC1AD845", "render");
    }


    @Test
    void theApplicationsPlannerEndsWithTheLocalAudioSink() {
        PlaybackPlanner configured = Planners.production();

        assertThat(configured.plan(item(AUDIO), EnumSet.of(Capability.LOCAL_AUDIO_SINK)).first()).isInstanceOf(Route.PlayLocally.class);
        assertThat(configured.plan(item(AUDIO), EnumSet.of(Capability.MEDIA_RENDERER, Capability.LOCAL_AUDIO_SINK)).first())
                .isInstanceOf(Route.Render.class);
    }


    private static List<String> keys(List<RouteStrategy> strategies) {
        return new PlaybackPlanner(strategies).plan(Planners.ladderItem(), Planners.LADDER_DEVICE).routes().stream()
                .map(Route::key).toList();
    }
}

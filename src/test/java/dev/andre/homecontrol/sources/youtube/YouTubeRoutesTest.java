package dev.andre.homecontrol.sources.youtube;

import dev.andre.homecontrol.core.Capability;
import dev.andre.homecontrol.core.playback.ContentItem;
import dev.andre.homecontrol.core.playback.ContentKind;
import dev.andre.homecontrol.core.playback.PlayableRef;
import dev.andre.homecontrol.core.playback.PlaybackPlanner;
import dev.andre.homecontrol.core.playback.Route;
import dev.andre.homecontrol.core.playback.RouteStrategies;
import dev.andre.homecontrol.testsupport.Planners;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** YouTube items across the device kinds, with the application's planner (spec order). */
class YouTubeRoutesTest {

    private static final URI WATCH = URI.create("https://www.youtube.com/watch?v=aqz-KE-bpKQ");
    private static final PlayableRef.AppLink LINK = new PlayableRef.AppLink(WATCH, "youtube");

    private final PlaybackPlanner planner = Planners.production();

    /** Written out like YouTubeVideo.toItem(). */
    private final ContentItem item = new ContentItem("aqz-KE-bpKQ", "youtube", ContentKind.VIDEO, "Big Buck Bunny",
            "Blender", URI.create("/sources/youtube/thumbnails/aqz-KE-bpKQ"), List.of(LINK));
    private final ContentItem lounge = item.withPlayables(List.of(LINK, new YouTubeLoungeRef("aqz-KE-bpKQ")));

    @Test
    void androidTvOpensTheYouTubeApp() {
        Route route = planner.plan(item, EnumSet.of(Capability.REMOTE_KEYS, Capability.VOLUME,
                Capability.APP_LINK)).first();

        assertThat(route).isEqualTo(new Route.OpenAppLink(WATCH, "youtube"));
        assertThat(route.describe()).isEqualTo("Open in the YouTube app");
    }

    @Test
    void smartTvsUseTheSameAppLink() {
        // webOS and Tizen both declare keys and app links; their adapters translate the link to contentTarget / DIAL.
        assertThat(planner.plan(lounge, EnumSet.of(Capability.REMOTE_KEYS, Capability.VOLUME,
                Capability.APP_LINK)).first()).isEqualTo(new Route.OpenAppLink(WATCH, "youtube"));
    }

    @Test
    void aCastOnlyDeviceWithoutTheSwitchCannotPlayIt() {
        assertThat(planner.plan(item, EnumSet.of(Capability.CAST_RECEIVER, Capability.VOLUME)).first())
                .isEqualTo(new Route.Unroutable("this device cannot open app links"));
    }

    @Test
    void aCastOnlyDeviceWithTheSwitchCastsThroughLounge() {
        Route route = planner.plan(lounge, EnumSet.of(Capability.CAST_RECEIVER, Capability.VOLUME)).first();

        assertThat(route).isEqualTo(new YouTubeLoungeRoute("aqz-KE-bpKQ"));
        assertThat(route.describe()).isEqualTo("Cast with the YouTube receiver (best effort)");
    }

    @Test
    void aMergedShieldPrefersTheAppAndOffersLoungeNext() {
        EnumSet<Capability> shield = EnumSet.of(Capability.REMOTE_KEYS, Capability.VOLUME,
                Capability.APP_LINK, Capability.CAST_RECEIVER);

        assertThat(planner.plan(lounge, shield).first()).isEqualTo(new Route.OpenAppLink(WATCH, "youtube"));
        assertThat(planner.plan(lounge, shield).routes())
                .containsExactly(new Route.OpenAppLink(WATCH, "youtube"), new YouTubeLoungeRoute("aqz-KE-bpKQ"));
    }

    @Test
    void aSpeakerCannotPlayYouTube() {
        assertThat(planner.plan(lounge, EnumSet.of(Capability.MEDIA_RENDERER, Capability.VOLUME)).first())
                .isEqualTo(new Route.Unroutable("this device cannot open app links; this device is not a Cast receiver"));
    }

    @Test
    void loungeNeedsACastReceiver() {
        PlaybackPlanner withLounge = new PlaybackPlanner(List.of(RouteStrategies.appLink(),
                new YouTubeConfiguration().youTubeLoungeStrategy()));
        ContentItem loungeOnly = item.withPlayables(List.of(new YouTubeLoungeRef("aqz-KE-bpKQ")));

        assertThat(withLounge.plan(loungeOnly, EnumSet.of(Capability.APP_LINK)).first())
                .isEqualTo(new Route.Unroutable("this device is not a Cast receiver"));
        assertThat(withLounge.plan(loungeOnly, EnumSet.of(Capability.CAST_RECEIVER)).first())
                .isEqualTo(new YouTubeLoungeRoute("aqz-KE-bpKQ"));
    }
}

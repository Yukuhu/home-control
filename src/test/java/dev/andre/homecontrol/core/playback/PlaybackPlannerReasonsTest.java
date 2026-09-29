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

/** One reason per playable kind when no strategy routes it: a planner without strategies routes nothing. */
class PlaybackPlannerReasonsTest {

    private static final String NOT_CAST_RECEIVER = "this device is not a Cast receiver";
    private static final PlayableRef.StreamUrl AUDIO =
            new PlayableRef.StreamUrl(URI.create("http://nas/a.mp3"), "audio/mpeg");
    private static final PlayableRef.StreamUrl VIDEO =
            new PlayableRef.StreamUrl(URI.create("http://nas/f.mp4"), "video/mp4");

    private final PlaybackPlanner planner = new PlaybackPlanner(List.of());

    private String reason(PlayableRef ref, Set<Capability> capabilities) {
        Route route = planner.plan(new ContentItem("x", "test", ContentKind.VIDEO, "Title", null, null, List.of(ref)),
                capabilities);
        assertThat(route).isInstanceOf(Route.Unroutable.class);
        return ((Route.Unroutable) route).reason();
    }

    @Test
    void anAppLinkFailsOnlyForLackingTheCapability() {
        PlayableRef.AppLink link = new PlayableRef.AppLink(URI.create("https://x"), "web");

        assertThat(reason(link, EnumSet.of(Capability.CAST_RECEIVER))).isEqualTo("this device cannot open app links");
        assertThat(reason(link, EnumSet.of(Capability.APP_LINK))).isEqualTo("no route to this device");
    }

    @Test
    void everyCastReferenceNeedsACastReceiver() {
        Set<Capability> none = EnumSet.noneOf(Capability.class);

        assertThat(reason(new PlayableRef.WorkflowCast("wf", 1, "entry"), none)).isEqualTo(NOT_CAST_RECEIVER);
        assertThat(reason(new PlayableRef.CastLoad("F007D354", Map.of()), none)).isEqualTo(NOT_CAST_RECEIVER);
        assertThat(reason(new PlayableRef.CastMessage(new Action.CastMessage("F007D354", "urn:x-cast:x", Map.of()),
                "a receiver"), none))
                .isEqualTo(NOT_CAST_RECEIVER);
        assertThat(reason(new PlayableRef.YouTubeLounge("abc"), none)).isEqualTo(NOT_CAST_RECEIVER);
    }

    @Test
    void aStreamReasonDependsOnWhatTheDeviceCanPlay() {
        assertThat(reason(VIDEO, EnumSet.of(Capability.LOCAL_AUDIO_SINK)))
                .isEqualTo("a Bluetooth speaker plays audio streams only");
        assertThat(reason(AUDIO, EnumSet.of(Capability.LOCAL_AUDIO_SINK))).isEqualTo("the stream was not accepted");
        assertThat(reason(VIDEO, EnumSet.of(Capability.LOCAL_AUDIO_SINK, Capability.MEDIA_RENDERER)))
                .isEqualTo("the stream was not accepted");
        assertThat(reason(VIDEO, EnumSet.of(Capability.CAST_RECEIVER))).isEqualTo("the stream was not accepted");
        assertThat(reason(VIDEO, EnumSet.of(Capability.MEDIA_RENDERER))).isEqualTo("the stream was not accepted");
        assertThat(reason(VIDEO, EnumSet.of(Capability.APP_LINK))).isEqualTo("this device cannot play a direct stream");
    }

    @Test
    void everyJellyfinReferenceNamesWhatIsMissing() {
        Set<Capability> all = EnumSet.allOf(Capability.class);

        assertThat(reason(new PlayableRef.JellyfinItem("srv", "item-1", 0), all))
                .isEqualTo("Jellyfin is switched off on this server");
        assertThat(reason(new PlayableRef.JellyfinSession("s", "item-1", 0, "Android TV"), all))
                .isEqualTo("the open Jellyfin app cannot be controlled");
        assertThat(reason(new PlayableRef.JellyfinVlc("item-1"), all)).isEqualTo("VLC cannot be opened on this device");
        assertThat(reason(new PlayableRef.JellyfinApp("item-1", 0), all))
                .isEqualTo("the Jellyfin app cannot be started on this device");
    }

    @Test
    void theSameReasonIsGivenOnce() {
        ContentItem item = new ContentItem("x", "test", ContentKind.VIDEO, "Title", null, null,
                List.of(new PlayableRef.YouTubeLounge("a"), new PlayableRef.CastLoad("F007D354", Map.of()), VIDEO));

        assertThat(planner.plan(item, EnumSet.noneOf(Capability.class)))
                .isEqualTo(new Route.Unroutable(NOT_CAST_RECEIVER + "; this device cannot play a direct stream"));
    }
}

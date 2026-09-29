package dev.andre.homecontrol.testsupport;

import dev.andre.homecontrol.core.Action;
import dev.andre.homecontrol.core.Capability;
import dev.andre.homecontrol.core.playback.ContentItem;
import dev.andre.homecontrol.core.playback.ContentKind;
import dev.andre.homecontrol.core.playback.PlayableRef;
import dev.andre.homecontrol.core.playback.PlaybackPlanner;
import dev.andre.homecontrol.core.playback.RouteStrategies;
import dev.andre.homecontrol.core.playback.RouteStrategy;
import dev.andre.homecontrol.sources.jellyfin.JellyfinPlayable;
import dev.andre.homecontrol.sources.jellyfin.JellyfinSessionStrategy;
import dev.andre.homecontrol.sources.workflows.WorkflowConfiguration;
import dev.andre.homecontrol.sources.youtube.YouTubeConfiguration;
import dev.andre.homecontrol.sources.youtube.YouTubeLoungeRef;
import java.net.URI;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The playback planner the application wires, for tests that plan without a Spring context. */
public final class Planners {

    private Planners() {
    }

    /** Core's strategies and every source's, as the application has them with all modules on. */
    public static PlaybackPlanner production() {
        return new PlaybackPlanner(strategies());
    }

    /** The strategies {@link #production()} plans with: core's six first, then the sources', not in ladder order. */
    public static List<RouteStrategy> strategies() {
        List<RouteStrategy> strategies = new ArrayList<>(RouteStrategies.core());
        strategies.add(new JellyfinSessionStrategy());
        strategies.add(new YouTubeConfiguration().youTubeLoungeStrategy());
        strategies.add(new WorkflowConfiguration().workflowCastStrategy());
        return List.copyOf(strategies);
    }

    /** A device that can take every rung of the ladder. */
    public static final Set<Capability> LADDER_DEVICE = Set.copyOf(EnumSet.of(Capability.JELLYFIN_CLIENT,
            Capability.APP_LINK, Capability.CAST_RECEIVER, Capability.MEDIA_RENDERER, Capability.LOCAL_AUDIO_SINK));

    /** The keys of the routes {@link #ladderItem()} gets on {@link #LADDER_DEVICE}: one per rung, in ladder order. */
    public static final List<String> LADDER_KEYS = List.of("jellyfin-session", "app-link", "youtube-lounge",
            "cast-message:F007D354", "cast:F007D354", "cast:CC1AD845", "render", "local-audio");

    /** An item with a reference for every rung, handed over in no particular order. */
    public static ContentItem ladderItem() {
        return new ContentItem("x", "test", ContentKind.VIDEO, "Title", null, null, List.of(
                new PlayableRef.StreamUrl(URI.create("http://nas.local/a.mp4"), "video/mp4"),
                new PlayableRef.StreamUrl(URI.create("http://nas.local/b.mp3"), "audio/mpeg"),
                new PlayableRef.CastLoad("F007D354", Map.of("contentId", "item-1")),
                new YouTubeLoungeRef("abc"),
                new PlayableRef.CastMessage(new Action.CastMessage("F007D354", "urn:x-cast:com.connectsdk", Map.of()),
                        "the Jellyfin receiver"),
                new PlayableRef.AppLink(URI.create("https://www.youtube.com/watch?v=abc"), "youtube"),
                new JellyfinPlayable.Session("s1", "item-1", 0, "Android TV")));
    }

    /** What a planner without strategies says when {@code ref} is an item's only reference: its reason alone. */
    public static String unroutableReason(PlayableRef ref, Set<Capability> capabilities) {
        ContentItem item = new ContentItem("x", "test", ContentKind.VIDEO, "Title", null, null, List.of(ref));
        return new PlaybackPlanner(List.of()).plan(item, capabilities).reason();
    }
}

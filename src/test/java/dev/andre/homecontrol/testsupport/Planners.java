package dev.andre.homecontrol.testsupport;

import dev.andre.homecontrol.core.Capability;
import dev.andre.homecontrol.core.playback.ContentItem;
import dev.andre.homecontrol.core.playback.ContentKind;
import dev.andre.homecontrol.core.playback.PlayableRef;
import dev.andre.homecontrol.core.playback.PlaybackPlanner;
import dev.andre.homecontrol.core.playback.RouteStrategies;
import dev.andre.homecontrol.core.playback.RouteStrategy;
import dev.andre.homecontrol.sources.jellyfin.JellyfinSessionStrategy;
import dev.andre.homecontrol.sources.workflows.WorkflowConfiguration;
import dev.andre.homecontrol.sources.youtube.YouTubeConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** The playback planner the application wires, for tests that plan without a Spring context. */
public final class Planners {

    private Planners() {
    }

    /** Core's strategies and every source's, as the application has them with all modules on. */
    public static PlaybackPlanner production() {
        return new PlaybackPlanner(strategies());
    }

    /** The strategies {@link #production()} plans with, in ladder order. */
    public static List<RouteStrategy> strategies() {
        List<RouteStrategy> strategies = new ArrayList<>(RouteStrategies.core());
        strategies.add(new JellyfinSessionStrategy());
        strategies.add(new YouTubeConfiguration().youTubeLoungeStrategy());
        strategies.add(new WorkflowConfiguration().workflowCastStrategy());
        return List.copyOf(strategies);
    }

    /** What a planner without strategies says when {@code ref} is an item's only reference: its reason alone. */
    public static String unroutableReason(PlayableRef ref, Set<Capability> capabilities) {
        ContentItem item = new ContentItem("x", "test", ContentKind.VIDEO, "Title", null, null, List.of(ref));
        return new PlaybackPlanner(List.of()).plan(item, capabilities).reason();
    }
}

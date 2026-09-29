package dev.andre.homecontrol.testsupport;

import dev.andre.homecontrol.core.playback.JellyfinSessionStrategy;
import dev.andre.homecontrol.core.playback.PlaybackPlanner;
import dev.andre.homecontrol.core.playback.RouteStrategies;
import dev.andre.homecontrol.core.playback.RouteStrategy;
import dev.andre.homecontrol.core.playback.WorkflowCastStrategy;
import dev.andre.homecontrol.core.playback.YouTubeLoungeStrategy;

import java.util.ArrayList;
import java.util.List;

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
        strategies.add(new YouTubeLoungeStrategy());
        strategies.add(new WorkflowCastStrategy());
        return List.copyOf(strategies);
    }
}

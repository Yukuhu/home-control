package dev.andre.homecontrol.core.playback;

import dev.andre.homecontrol.core.Capability;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Matches an item's playable references to a device's capabilities. Pure: no I/O, no
 * device state, so the capability × reference matrix is unit-testable (spec §12).
 */
public class PlaybackPlanner {

    private final List<RouteStrategy> strategies;

    public PlaybackPlanner(List<RouteStrategy> strategies) {
        this.strategies = List.copyOf(strategies);
    }

    public Route plan(ContentItem item, Set<Capability> capabilities) {
        if (item.playables().isEmpty()) {
            return new Route.Unroutable("This item has nothing playable");
        }
        for (RouteStrategy strategy : strategies) {
            Optional<Route> route = strategy.route(item, capabilities);
            if (route.isPresent()) {
                return route.get();
            }
        }
        return new Route.Unroutable(String.join("; ", explain(item, capabilities)));
    }

    /**
     * Reasons no strategy routed, one per distinct playable kind. An {@link PlayableRef.AppLink}
     * only ever fails here for lacking the capability: with {@link Capability#APP_LINK} present,
     * {@link AppLinkStrategy} would already have routed it, so there is no "was not accepted" case.
     * Falls back to a generic reason when none applies (e.g. a planner without that strategy).
     */
    private static List<String> explain(ContentItem item, Set<Capability> capabilities) {
        Set<String> reasons = new LinkedHashSet<>();
        for (PlayableRef ref : item.playables()) {
            if (ref instanceof PlayableRef.AppLink) {
                if (!capabilities.contains(Capability.APP_LINK)) {
                    reasons.add("this device cannot open app links");
                }
            } else {
                reasons.add(ref.kindLabel() + " playback is not supported yet");
            }
        }
        if (reasons.isEmpty()) {
            reasons.add("no route to this device");
        }
        return new ArrayList<>(reasons);
    }
}

package dev.andre.homecontrol.core.playback;

import dev.andre.homecontrol.core.Capability;

import java.util.ArrayList;
import java.util.HashSet;
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
        return routes(item, capabilities).stream().findFirst()
                .orElseGet(() -> new Route.Unroutable(String.join("; ", explain(item, capabilities))));
    }

    /** Every route the strategies offer, in preference order (spec §5.3). Pure. */
    public List<Route> routes(ContentItem item, Set<Capability> capabilities) {
        List<Route> routes = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        for (RouteStrategy strategy : strategies) {
            strategy.route(item, capabilities)
                    .filter(route -> !(route instanceof Route.Unroutable))
                    .filter(route -> keys.add(RouteKeys.key(route)))
                    .ifPresent(routes::add);
        }
        return routes;
    }

    /**
     * Reasons no strategy routed, one per distinct playable kind. An {@link PlayableRef.AppLink}
     * only ever fails here for lacking the capability: with {@link Capability#APP_LINK} present,
     * {@link AppLinkStrategy} would already have routed it, so there is no "was not accepted" case.
     * The same holds for {@link PlayableRef.CastLoad}/{@link PlayableRef.CastMessage}/
     * {@link PlayableRef.StreamUrl} and {@link Capability#CAST_RECEIVER}: with it present,
     * {@link CastMessageStrategy}/{@link CastLoadStrategy}/{@link CastStreamStrategy} would
     * already have routed it, so reaching here always means the capability is missing. Falls
     * back to a generic reason when none applies (e.g. a planner without that strategy).
     */
    private static List<String> explain(ContentItem item, Set<Capability> capabilities) {
        Set<String> reasons = new LinkedHashSet<>();
        for (PlayableRef ref : item.playables()) {
            switch (ref) {
                case PlayableRef.AppLink ignored -> {
                    if (!capabilities.contains(Capability.APP_LINK)) {
                        reasons.add("this device cannot open app links");
                    }
                }
                case PlayableRef.CastLoad ignored -> reasons.add("this device is not a Cast receiver");
                case PlayableRef.CastMessage ignored -> reasons.add("this device is not a Cast receiver");
                case PlayableRef.StreamUrl ignored -> reasons.add("this device cannot play a direct stream");
                case PlayableRef.JellyfinItem ignored -> reasons.add("Jellyfin is switched off on this server");
                case PlayableRef.JellyfinSession ignored -> reasons.add("the open Jellyfin app cannot be controlled");
            }
        }
        if (reasons.isEmpty()) {
            reasons.add("no route to this device");
        }
        return new ArrayList<>(reasons);
    }
}

package dev.andre.homecontrol.core.playback;

import dev.andre.homecontrol.core.Capability;

import java.util.Optional;
import java.util.Set;

/** Offers a route on one rung of the preference ladder in spec §5.3. The planner tries strategies in rung order. */
public interface RouteStrategy {

    /** Where this strategy stands on the ladder; strategies on one rung are tried in the order they were given. */
    Rung rung();

    Optional<Route> route(ContentItem item, Set<Capability> capabilities);
}

package dev.andre.homecontrol.core.playback;

import dev.andre.homecontrol.core.Capability;

import java.util.Optional;
import java.util.Set;

/** One rung of the preference ladder in spec §5.3. Strategies are tried in list order. */
@FunctionalInterface
public interface RouteStrategy {
    Optional<Route> route(ContentItem item, Set<Capability> capabilities);
}

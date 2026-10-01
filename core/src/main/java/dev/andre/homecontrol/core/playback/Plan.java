package dev.andre.homecontrol.core.playback;

import java.util.List;

/** The planner's answer: every route in preference order, or the reason there is none. */
public record Plan(List<Route> routes, String reason) {

    public Plan {
        routes = List.copyOf(routes);
    }

    /** The preferred route, or {@link Route.Unroutable} with the reason. */
    public Route first() {
        return routes.isEmpty() ? new Route.Unroutable(reason) : routes.getFirst();
    }
}

package dev.andre.homecontrol.web;

import dev.andre.homecontrol.core.playback.Route;
import dev.andre.homecontrol.core.playback.RouteKeys;

/** A route as the browser is allowed to see it: a stable key and a human description, never a payload. */
public record RouteView(String key, String description, boolean optimistic) {
    public static RouteView of(Route route) {
        return route == null ? null : new RouteView(RouteKeys.key(route), route.describe(), RouteKeys.optimistic(route));
    }
}

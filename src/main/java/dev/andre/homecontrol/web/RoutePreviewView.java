package dev.andre.homecontrol.web;

import dev.andre.homecontrol.core.playback.Route;
import dev.andre.homecontrol.playback.PlaybackPreview;

import java.util.List;

/** GET /devices/{id}/route-preview response: the route the play sheet would take, plus its alternatives. */
public record RoutePreviewView(String deviceId, String deviceName, boolean playable, RouteView route,
                                List<RouteView> alternatives, String reason) {
    public static RoutePreviewView of(PlaybackPreview preview) {
        List<Route> routes = preview.routes();
        RouteView route = routes.isEmpty() ? null : RouteView.of(routes.getFirst());
        List<RouteView> alternatives = routes.isEmpty() ? List.of()
                : routes.subList(1, routes.size()).stream().map(RouteView::of).toList();
        return new RoutePreviewView(preview.device().id(), preview.device().name(), !routes.isEmpty(),
                route, alternatives, preview.reason());
    }
}

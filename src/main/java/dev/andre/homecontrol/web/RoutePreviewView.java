package dev.andre.homecontrol.web;

import dev.andre.homecontrol.core.playback.ContentKind;
import dev.andre.homecontrol.core.playback.Route;
import dev.andre.homecontrol.playback.PlaybackPreview;

import java.util.List;

/** GET /devices/{id}/route-preview response: the route the play sheet would take, plus its alternatives. */
public record RoutePreviewView(String deviceId, String deviceName, boolean playable, RouteView route,
                                List<RouteView> alternatives, String reason, PinOfferView pin) {
    public static RoutePreviewView of(PlaybackPreview preview) {
        return of(preview, null);
    }

    public static RoutePreviewView of(PlaybackPreview preview, PinOfferView pin) {
        return of(preview, pin, null);
    }

    /** Same, but lets each route's description name what is being opened by the item's kind. */
    public static RoutePreviewView of(PlaybackPreview preview, PinOfferView pin, ContentKind kind) {
        List<Route> routes = preview.routes();
        RouteView route = routes.isEmpty() ? null : RouteView.of(routes.getFirst(), kind);
        List<RouteView> alternatives = routes.isEmpty() ? List.of()
                : routes.subList(1, routes.size()).stream().map(r -> RouteView.of(r, kind)).toList();
        return new RoutePreviewView(preview.device().id(), preview.device().name(), !routes.isEmpty(),
                route, alternatives, preview.reason(), pin);
    }
}

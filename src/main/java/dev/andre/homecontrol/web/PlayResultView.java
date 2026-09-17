package dev.andre.homecontrol.web;

/** POST /devices/{id}/play-attempt response. {@code next} is the first remaining route, or null. */
public record PlayResultView(boolean played, String deviceId, String deviceName, RouteView route, RouteView next,
                              String message) {
}

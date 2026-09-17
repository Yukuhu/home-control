package dev.andre.homecontrol.playback;

import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.playback.Route;

import java.util.List;

/** What the play sheet shows before anything runs. {@code routes} empty iff {@code reason} is non-null. */
public record PlaybackPreview(Device device, List<Route> routes, String reason) {
    public PlaybackPreview {
        routes = List.copyOf(routes);
    }
}

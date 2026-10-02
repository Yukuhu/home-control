package dev.andre.homecontrol.sources.youtube;

import dev.andre.homecontrol.core.playback.DelegatedRoute;

/**
 * Start the video on a Cast receiver through its best-effort remote-control pairing. Executed by
 * {@link YouTubeLoungeRouteExecutor}, not an adapter; the device part goes through DeviceCommands.query.
 */
public record YouTubeLoungeRoute(String videoId) implements DelegatedRoute {

    /** The key {@link YouTubeLoungeRouteExecutor} is found by. */
    public static final String ROUTE_KEY = "youtube-lounge";

    @Override
    public String source() {
        return "YouTube";
    }

    @Override
    public String key() {
        return ROUTE_KEY;
    }

    @Override
    public String describe() {
        return "Cast with the YouTube receiver (best effort)";
    }
}

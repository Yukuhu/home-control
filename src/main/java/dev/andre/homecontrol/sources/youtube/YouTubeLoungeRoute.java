package dev.andre.homecontrol.sources.youtube;

import dev.andre.homecontrol.core.playback.DelegatedRoute;

/**
 * Start the video on a Cast receiver through its best-effort remote-control pairing. Executed by
 * {@link YouTubeLoungeRouteExecutor}, not an adapter; the device part goes through DeviceCommands.query.
 */
public record YouTubeLoungeRoute(String videoId) implements DelegatedRoute {
    @Override
    public String source() {
        return "YouTube";
    }

    @Override
    public String key() {
        return "youtube-lounge";
    }

    @Override
    public String describe() {
        return "Cast with the YouTube receiver (best effort)";
    }
}

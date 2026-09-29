package dev.andre.homecontrol.core.playback;

import dev.andre.homecontrol.core.Device;

import java.util.Set;

/** Runs the delegated routes whose keys it lists (e.g. a Jellyfin session's PlayNow). */
public interface RouteExecutor {

    /** The keys of the routes this executor runs; no two executors share one. */
    Set<String> keys();

    /** Throws {@code ActionFailedException} with a user-facing reason when the command is refused. */
    void execute(DelegatedRoute route, Device device);
}

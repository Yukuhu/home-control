package dev.andre.homecontrol.core.playback;

import dev.andre.homecontrol.core.Device;

/** Runs routes that go through a content server instead of a device adapter (e.g. Jellyfin session PlayNow). */
public interface RouteExecutor {

    boolean executes(Route route);

    /** Throws {@code ActionFailedException} with a user-facing reason when the command is refused. */
    void execute(Route route, Device device);
}

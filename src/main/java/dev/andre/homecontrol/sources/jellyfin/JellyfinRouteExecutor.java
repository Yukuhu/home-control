package dev.andre.homecontrol.sources.jellyfin;

import dev.andre.homecontrol.core.ActionFailedException;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.playback.Route;
import dev.andre.homecontrol.core.playback.RouteExecutor;

public class JellyfinRouteExecutor implements RouteExecutor {

    private final JellyfinSessions sessions;

    public JellyfinRouteExecutor(JellyfinSessions sessions) {
        this.sessions = sessions;
    }

    @Override
    public boolean executes(Route route) {
        return route instanceof Route.JellyfinSession;
    }

    @Override
    public void execute(Route route, Device device) {
        Route.JellyfinSession session = (Route.JellyfinSession) route;
        try {
            sessions.playNow(session.sessionId(), session.itemId(), session.startPositionTicks());
        } catch (JellyfinException | IllegalArgumentException e) {
            throw new ActionFailedException("Jellyfin could not start playback on " + device.name() + " (" + e.getMessage() + ")");
        }
    }
}

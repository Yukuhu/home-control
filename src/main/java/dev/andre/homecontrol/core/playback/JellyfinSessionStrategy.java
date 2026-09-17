package dev.andre.homecontrol.core.playback;

import dev.andre.homecontrol.core.Capability;

import java.util.Optional;
import java.util.Set;

/**
 * Rung 1 of spec §5.3: the Jellyfin app is open on the device. It resumes with the user's own
 * profile, audio and subtitle choices, so it beats relaunching anything.
 */
public class JellyfinSessionStrategy implements RouteStrategy {

    @Override
    public Optional<Route> route(ContentItem item, Set<Capability> capabilities) {
        if (!capabilities.contains(Capability.JELLYFIN_CLIENT)) {
            return Optional.empty();
        }
        return item.playables().stream()
                .filter(PlayableRef.JellyfinSession.class::isInstance)
                .map(PlayableRef.JellyfinSession.class::cast)
                .findFirst()
                .map(s -> new Route.JellyfinSession(s.sessionId(), s.itemId(), s.startPositionTicks(), s.client()));
    }
}

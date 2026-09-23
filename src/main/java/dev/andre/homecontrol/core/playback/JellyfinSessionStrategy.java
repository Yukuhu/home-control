package dev.andre.homecontrol.core.playback;

import dev.andre.homecontrol.core.Capability;

import java.util.Optional;
import java.util.Set;

/**
 * Prefer the native Jellyfin app, either an existing session or an Android TV app that can
 * be started on demand. It preserves the user's profile, audio and subtitle choices.
 */
public class JellyfinSessionStrategy implements RouteStrategy {

    @Override
    public Optional<Route> route(ContentItem item, Set<Capability> capabilities) {
        if (!capabilities.contains(Capability.JELLYFIN_CLIENT)) {
            return Optional.empty();
        }
        Optional<Route> vlc = item.playables().stream().filter(PlayableRef.JellyfinVlc.class::isInstance)
                .map(PlayableRef.JellyfinVlc.class::cast).findFirst()
                .map(ref -> new Route.JellyfinVlc(ref.itemId()));
        if (vlc.isPresent() && capabilities.containsAll(Set.of(Capability.APP_LINK, Capability.REMOTE_KEYS))) {
            return vlc;
        }
        Optional<Route> open = item.playables().stream()
                .filter(PlayableRef.JellyfinSession.class::isInstance)
                .map(PlayableRef.JellyfinSession.class::cast)
                .findFirst()
                .map(s -> new Route.JellyfinSession(s.sessionId(), s.itemId(), s.startPositionTicks(), s.client()));
        return open.or(() -> item.playables().stream()
                .filter(PlayableRef.JellyfinApp.class::isInstance)
                .map(PlayableRef.JellyfinApp.class::cast)
                .findFirst()
                .map(app -> new Route.JellyfinApp(app.itemId(), app.startPositionTicks())));
    }
}

package dev.andre.homecontrol.sources.jellyfin;

import dev.andre.homecontrol.core.Capability;
import dev.andre.homecontrol.core.playback.ContentItem;
import dev.andre.homecontrol.core.playback.Route;
import dev.andre.homecontrol.core.playback.RouteStrategy;
import dev.andre.homecontrol.core.playback.Rung;

import java.util.Optional;
import java.util.Set;

/**
 * Prefer the native Jellyfin app, either an existing session or an Android TV app that can
 * be started on demand. It preserves the user's profile, audio and subtitle choices.
 */
public class JellyfinSessionStrategy implements RouteStrategy {
    @Override
    public Rung rung() {
        return Rung.NATIVE_APP;
    }

    @Override
    public Optional<Route> route(ContentItem item, Set<Capability> capabilities) {
        if (!capabilities.contains(Capability.JELLYFIN_CLIENT)) {
            return Optional.empty();
        }
        Optional<Route> vlc = item.playables().stream().filter(JellyfinPlayable.Vlc.class::isInstance)
                .map(JellyfinPlayable.Vlc.class::cast).findFirst()
                .map(ref -> new JellyfinRoute.Vlc(ref.itemId()));
        if (vlc.isPresent() && capabilities.containsAll(Set.of(Capability.APP_LINK, Capability.REMOTE_KEYS))) {
            return vlc;
        }
        Optional<Route> open = item.playables().stream()
                .filter(JellyfinPlayable.Session.class::isInstance)
                .map(JellyfinPlayable.Session.class::cast)
                .findFirst()
                .map(s -> new JellyfinRoute.Session(s.sessionId(), s.itemId(), s.startPositionTicks(), s.client()));
        return open.or(() -> item.playables().stream()
                .filter(JellyfinPlayable.App.class::isInstance)
                .map(JellyfinPlayable.App.class::cast)
                .findFirst()
                .map(app -> new JellyfinRoute.App(app.itemId(), app.startPositionTicks())));
    }
}

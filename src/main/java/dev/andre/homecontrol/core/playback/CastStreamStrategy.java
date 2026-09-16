package dev.andre.homecontrol.core.playback;

import dev.andre.homecontrol.core.Capability;

import java.util.Optional;
import java.util.Set;

/**
 * Still rung 3: a direct stream on a Cast receiver goes through the Default Media Receiver —
 * after a source-built CastLoad, before rung 4 (DLNA/Sonos media renderers, sub-project I).
 */
public class CastStreamStrategy implements RouteStrategy {

    @Override
    public Optional<Route> route(ContentItem item, Set<Capability> capabilities) {
        if (!capabilities.contains(Capability.CAST_RECEIVER)) {
            return Optional.empty();
        }
        return item.playables().stream()
                .filter(PlayableRef.StreamUrl.class::isInstance)
                .map(PlayableRef.StreamUrl.class::cast)
                .findFirst()
                .map(stream -> new Route.Cast(CastLoads.DEFAULT_MEDIA_RECEIVER,
                        CastLoads.defaultMediaReceiver(stream, item.title())));
    }
}

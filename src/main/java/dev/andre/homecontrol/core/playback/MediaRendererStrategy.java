package dev.andre.homecontrol.core.playback;

import dev.andre.homecontrol.core.Capability;

import java.util.Optional;
import java.util.Set;

/** Rung 4 of spec §5.3: a media renderer (DLNA, Sonos) plays the item's direct stream. After every Cast rung. */
public class MediaRendererStrategy implements RouteStrategy {

    @Override
    public Optional<Route> route(ContentItem item, Set<Capability> capabilities) {
        if (!capabilities.contains(Capability.MEDIA_RENDERER)) {
            return Optional.empty();
        }
        return item.playables().stream()
                .filter(PlayableRef.StreamUrl.class::isInstance)
                .map(PlayableRef.StreamUrl.class::cast)
                .findFirst()
                .map(stream -> new Route.Render(stream.url(), stream.mimeType(), item.title(), item.subtitle()));
    }
}

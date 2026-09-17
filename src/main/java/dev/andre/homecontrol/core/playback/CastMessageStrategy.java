package dev.andre.homecontrol.core.playback;

import dev.andre.homecontrol.core.Capability;

import java.util.Optional;
import java.util.Set;

/** Rung 3 of spec §5.3, first variant: a receiver app driven by custom messages (Jellyfin), before LOADs and bare streams. */
public class CastMessageStrategy implements RouteStrategy {

    @Override
    public Optional<Route> route(ContentItem item, Set<Capability> capabilities) {
        if (!capabilities.contains(Capability.CAST_RECEIVER)) {
            return Optional.empty();
        }
        return item.playables().stream()
                .filter(PlayableRef.CastMessage.class::isInstance)
                .map(PlayableRef.CastMessage.class::cast)
                .findFirst()
                .map(m -> new Route.CastMessage(m.receiverAppId(), m.namespace(), m.message(), m.receiverLabel()));
    }
}

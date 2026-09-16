package dev.andre.homecontrol.core.playback;

import dev.andre.homecontrol.core.Capability;

import java.util.Optional;
import java.util.Set;

/** Rung 3 of spec §5.3: a Cast receiver and a ready-made CastLoad (Jellyfin receiver, DMR, …). */
public class CastLoadStrategy implements RouteStrategy {

    @Override
    public Optional<Route> route(ContentItem item, Set<Capability> capabilities) {
        if (!capabilities.contains(Capability.CAST_RECEIVER)) {
            return Optional.empty();
        }
        return item.playables().stream()
                .filter(PlayableRef.CastLoad.class::isInstance)
                .map(PlayableRef.CastLoad.class::cast)
                .findFirst()
                .map(load -> new Route.Cast(load.receiverAppId(), load.payload()));
    }
}

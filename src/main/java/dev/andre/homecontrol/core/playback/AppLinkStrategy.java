package dev.andre.homecontrol.core.playback;

import dev.andre.homecontrol.core.Capability;

import java.util.Optional;
import java.util.Set;

/** Rung 2 of spec §5.3: the device can open app links and the item has one. */
public class AppLinkStrategy implements RouteStrategy {

    @Override
    public Optional<Route> route(ContentItem item, Set<Capability> capabilities) {
        if (!capabilities.contains(Capability.APP_LINK)) {
            return Optional.empty();
        }
        return item.playables().stream()
                .filter(PlayableRef.AppLink.class::isInstance)
                .map(PlayableRef.AppLink.class::cast)
                .findFirst()
                .map(link -> new Route.OpenAppLink(link.uri(), link.service()));
    }
}

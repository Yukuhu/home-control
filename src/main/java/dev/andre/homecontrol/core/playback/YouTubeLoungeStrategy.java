package dev.andre.homecontrol.core.playback;

import dev.andre.homecontrol.core.Capability;

import java.util.Optional;
import java.util.Set;

/**
 * Rung 3 of spec §5.3, before custom messages: a video a resolver attached for the receiver's
 * best-effort remote pairing (only for Cast devices whose switch is on). App links still come first.
 */
public class YouTubeLoungeStrategy implements RouteStrategy {

    @Override
    public Optional<Route> route(ContentItem item, Set<Capability> capabilities) {
        if (!capabilities.contains(Capability.CAST_RECEIVER)) {
            return Optional.empty();
        }
        return item.playables().stream()
                .filter(PlayableRef.YouTubeLounge.class::isInstance)
                .map(PlayableRef.YouTubeLounge.class::cast)
                .findFirst()
                .map(lounge -> new Route.YouTubeLounge(lounge.videoId()));
    }
}

package dev.andre.homecontrol.core.playback;

import dev.andre.homecontrol.core.Capability;
import java.util.Optional;
import java.util.Set;

/** Pure planning: credentials and media addresses are resolved only by the route executor. */
public final class WorkflowCastStrategy implements RouteStrategy {
    @Override public Optional<Route> route(ContentItem item, Set<Capability> capabilities) {
        if (!capabilities.contains(Capability.CAST_RECEIVER)) return Optional.empty();
        return item.playables().stream().filter(PlayableRef.WorkflowCast.class::isInstance)
                .map(PlayableRef.WorkflowCast.class::cast).findFirst()
                .map(ref -> new Route.WorkflowCast(ref.workflowId(), ref.revision(), ref.entryKey()));
    }
}

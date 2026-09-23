package dev.andre.homecontrol.core.playback;

import dev.andre.homecontrol.core.Capability;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Matches an item's playable references to a device's capabilities. Pure: no I/O, no
 * device state, so the capability × reference matrix is unit-testable (spec §12).
 */
public class PlaybackPlanner {

    private static final String NOT_CAST_RECEIVER = "this device is not a Cast receiver";

    private final List<RouteStrategy> strategies;

    public PlaybackPlanner(List<RouteStrategy> strategies) {
        this.strategies = List.copyOf(strategies);
    }

    public Route plan(ContentItem item, Set<Capability> capabilities) {
        if (item.playables().isEmpty()) {
            return new Route.Unroutable("This item has nothing playable");
        }
        return routes(item, capabilities).stream().findFirst()
                .orElseGet(() -> new Route.Unroutable(String.join("; ", explain(item, capabilities))));
    }

    /** Every route the strategies offer, in preference order (spec §5.3). Pure. */
    public List<Route> routes(ContentItem item, Set<Capability> capabilities) {
        List<Route> routes = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        for (RouteStrategy strategy : strategies) {
            strategy.route(item, capabilities)
                    .filter(route -> !(route instanceof Route.Unroutable))
                    .filter(route -> keys.add(RouteKeys.key(route)))
                    .ifPresent(routes::add);
        }
        return routes;
    }

    /**
     * Reasons no strategy routed, one per distinct playable kind. An {@link PlayableRef.AppLink}
     * only ever fails here for lacking the capability: with {@link Capability#APP_LINK} present,
     * {@link AppLinkStrategy} would already have routed it, so there is no "was not accepted" case.
     * The same holds for {@link PlayableRef.CastLoad}/{@link PlayableRef.CastMessage}/
     * {@link PlayableRef.YouTubeLounge}/{@link PlayableRef.StreamUrl} and {@link Capability#CAST_RECEIVER}:
     * with it present, {@link YouTubeLoungeStrategy}/{@link CastMessageStrategy}/{@link CastLoadStrategy}/
     * {@link CastStreamStrategy} would already have routed it, so reaching here always means the capability is missing —
     * except a {@link PlayableRef.StreamUrl} on a Cast receiver or media renderer whose strategy is absent or declined
     * it, which reads "the stream was not accepted". Falls
     * back to a generic reason when none applies (e.g. a planner without that strategy).
     */
    private static List<String> explain(ContentItem item, Set<Capability> capabilities) {
        Set<String> reasons = new LinkedHashSet<>();
        for (PlayableRef ref : item.playables()) {
            switch (ref) {
                case PlayableRef.AppLink _ -> {
                    if (!capabilities.contains(Capability.APP_LINK)) {
                        reasons.add("this device cannot open app links");
                    }
                }
                case PlayableRef.WorkflowCast _ -> reasons.add(NOT_CAST_RECEIVER);
                case PlayableRef.CastLoad _ -> reasons.add(NOT_CAST_RECEIVER);
                case PlayableRef.CastMessage _ -> reasons.add(NOT_CAST_RECEIVER);
                case PlayableRef.YouTubeLounge _ -> reasons.add(NOT_CAST_RECEIVER);
                case PlayableRef.StreamUrl stream -> reasons.add(
                        capabilities.contains(Capability.LOCAL_AUDIO_SINK) && !LocalAudioSinkStrategy.playable(stream)
                                && !capabilities.contains(Capability.CAST_RECEIVER) && !capabilities.contains(Capability.MEDIA_RENDERER)
                                ? "a Bluetooth speaker plays audio streams only"
                                : capabilities.contains(Capability.CAST_RECEIVER) || capabilities.contains(Capability.MEDIA_RENDERER)
                                        || capabilities.contains(Capability.LOCAL_AUDIO_SINK)
                                        ? "the stream was not accepted" : "this device cannot play a direct stream");
                case PlayableRef.JellyfinItem _ -> reasons.add("Jellyfin is switched off on this server");
                case PlayableRef.JellyfinSession _ -> reasons.add("the open Jellyfin app cannot be controlled");
                case PlayableRef.JellyfinApp _ -> reasons.add("the Jellyfin app cannot be started on this device");
            }
        }
        if (reasons.isEmpty()) {
            reasons.add("no route to this device");
        }
        return new ArrayList<>(reasons);
    }
}

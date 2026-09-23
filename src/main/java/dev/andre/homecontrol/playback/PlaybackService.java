package dev.andre.homecontrol.playback;

import dev.andre.homecontrol.core.ActionFailedException;
import dev.andre.homecontrol.core.Capability;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceNotFoundException;
import dev.andre.homecontrol.core.DeviceOfflineException;
import dev.andre.homecontrol.core.UnsupportedActionException;
import dev.andre.homecontrol.core.playback.ContentItem;
import dev.andre.homecontrol.core.playback.PlayableRef;
import dev.andre.homecontrol.core.playback.PlayableResolver;
import dev.andre.homecontrol.core.playback.PlaybackPlanner;
import dev.andre.homecontrol.core.playback.Route;
import dev.andre.homecontrol.core.playback.RouteExecutor;
import dev.andre.homecontrol.core.playback.RouteKeys;
import dev.andre.homecontrol.core.playback.UnroutableException;
import dev.andre.homecontrol.device.DeviceManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Resolve, plan, execute, report. Commands are ephemeral: a failure here is final. */
@Service
public class PlaybackService {

    private final DeviceManager devices;
    private final PlaybackPlanner planner;
    private final List<PlayableResolver> resolvers;
    private final List<RouteExecutor> executors;

    public PlaybackService(DeviceManager devices, PlaybackPlanner planner) {
        this(devices, planner, List.of(), List.of());
    }

    @Autowired
    public PlaybackService(DeviceManager devices, PlaybackPlanner planner,
                           ObjectProvider<PlayableResolver> resolvers, ObjectProvider<RouteExecutor> executors) {
        this(devices, planner, resolvers.orderedStream().toList(), executors.orderedStream().toList());
    }

    public PlaybackService(DeviceManager devices, PlaybackPlanner planner,
                           List<PlayableResolver> resolvers, List<RouteExecutor> executors) {
        this.devices = devices;
        this.planner = planner;
        this.resolvers = List.copyOf(resolvers);
        this.executors = List.copyOf(executors);
    }

    /** The route the item would take now, without playing it. May do I/O through resolvers. */
    public Route plan(ContentItem item, String deviceId) {
        return plan(item, device(deviceId));
    }

    public Route play(ContentItem item, String deviceId) {
        Device device = device(deviceId);
        Route route = plan(item, device);
        execute(route, device);
        return route;
    }

    /** The route the item would take now plus every alternative, without playing anything. May do I/O through resolvers. */
    public PlaybackPreview preview(ContentItem item, String deviceId) {
        Device device = device(deviceId);
        Resolved resolved = resolve(item, device);
        if (resolved.item().playables().isEmpty()) {
            String reason = resolved.notes().isEmpty()
                    ? "This item has nothing playable" : String.join("; ", resolved.notes());
            return new PlaybackPreview(device, List.of(), reason);
        }
        List<Route> routes = planner.routes(resolved.item(), resolved.capabilities());
        if (routes.isEmpty()) {
            return new PlaybackPreview(device, List.of(), explain(resolved));
        }
        return new PlaybackPreview(device, routes, null);
    }

    /**
     * Plays the first route the item and device agree on that is not in {@code skip} (route keys,
     * spec §5.4), and reports what is left to try. Commands are ephemeral: a failure is final,
     * not retried automatically.
     */
    public PlayAttempt attempt(ContentItem item, String deviceId, Set<String> skip) {
        PlaybackPreview preview = preview(item, deviceId);
        Device device = preview.device();
        List<Route> routes = preview.routes().stream().filter(route -> !skip.contains(RouteKeys.key(route))).toList();
        if (routes.isEmpty()) {
            return new PlayAttempt.Unroutable(device, skip.isEmpty() ? preview.reason() : "no other way to play this");
        }
        Route route = routes.getFirst();
        List<Route> remaining = routes.subList(1, routes.size());
        try {
            execute(route, device);
            return new PlayAttempt.Played(device, route, remaining);
        } catch (DeviceOfflineException | UnsupportedActionException | ActionFailedException e) {
            return new PlayAttempt.Failed(device, route, remaining, e);
        }
    }

    private record Resolved(ContentItem item, Set<Capability> capabilities, List<String> notes) {
    }

    private Resolved resolve(ContentItem item, Device device) {
        Set<Capability> capabilities = EnumSet.noneOf(Capability.class);
        capabilities.addAll(devices.capabilities(device.id()));
        List<PlayableRef> playables = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        for (PlayableRef ref : item.playables()) {
            Optional<PlayableResolver> resolver = resolvers.stream().filter(r -> r.resolves(ref)).findFirst();
            if (resolver.isEmpty()) {
                playables.add(ref);
                continue;
            }
            PlayableResolver.Resolution resolution = resolver.get().resolve(ref, item, device, Set.copyOf(capabilities));
            playables.addAll(resolution.playables());
            capabilities.addAll(resolution.liveCapabilities());
            notes.addAll(resolution.notes());
        }
        return new Resolved(item.withPlayables(playables), capabilities, notes);
    }

    private Route plan(ContentItem item, Device device) {
        Resolved resolved = resolve(item, device);
        if (resolved.item().playables().isEmpty() && !resolved.notes().isEmpty()) {
            return new Route.Unroutable(String.join("; ", resolved.notes()));
        }
        Route route = planner.plan(resolved.item(), resolved.capabilities());
        if (route instanceof Route.Unroutable unroutable && !resolved.notes().isEmpty()) {
            return new Route.Unroutable(String.join("; ", resolved.notes()) + "; " + unroutable.reason());
        }
        return route;
    }

    /** The planner's own reason nothing routes, prefixed with any resolver notes — shared wording with {@link #plan}. */
    private String explain(Resolved resolved) {
        Route route = planner.plan(resolved.item(), resolved.capabilities());
        String reason = route instanceof Route.Unroutable u ? u.reason() : "no route";
        return resolved.notes().isEmpty() ? reason : String.join("; ", resolved.notes()) + "; " + reason;
    }

    private void execute(Route route, Device device) {
        switch (route) {
            case Route.OpenAppLink open -> devices.execute(device.id(), open.action());
            case Route.Cast cast -> devices.execute(device.id(), cast.action());
            case Route.CastMessage message -> devices.execute(device.id(), message.action());
            case Route.Render render -> devices.execute(device.id(), render.action());
            case Route.PlayLocally local -> devices.execute(device.id(), local.action());
            case Route.JellyfinSession ignored -> executeJellyfin(route, device);
            case Route.JellyfinVlc ignored -> executeJellyfin(route, device);
            case Route.JellyfinApp ignored -> executeJellyfin(route, device);
            case Route.WorkflowCast workflow -> executors.stream()
                    .filter(executor -> executor.executes(workflow))
                    .findFirst()
                    .orElseThrow(() -> new UnroutableException(device.name() + ": Workflows are switched off on this server"))
                    .execute(workflow, device);
            case Route.YouTubeLounge lounge -> executors.stream()
                    .filter(executor -> executor.executes(lounge))
                    .findFirst()
                    .orElseThrow(() -> new UnroutableException(device.name() + ": YouTube is switched off on this server"))
                    .execute(lounge, device);
            case Route.Unroutable unroutable -> throw new UnroutableException(device.name() + ": " + unroutable.reason());
        }
    }

    private Device device(String deviceId) {
        return devices.device(deviceId)
                .orElseThrow(() -> new DeviceNotFoundException("No device with id " + deviceId));
    }

    private void executeJellyfin(Route route, Device device) {
        executors.stream().filter(executor -> executor.executes(route)).findFirst()
                .orElseThrow(() -> new UnroutableException(device.name() + ": Jellyfin is switched off on this server"))
                .execute(route, device);
    }
}

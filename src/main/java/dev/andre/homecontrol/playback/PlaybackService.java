package dev.andre.homecontrol.playback;

import dev.andre.homecontrol.core.ActionFailedException;
import dev.andre.homecontrol.core.Capability;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceCommands;
import dev.andre.homecontrol.core.DeviceNotFoundException;
import dev.andre.homecontrol.core.DeviceOfflineException;
import dev.andre.homecontrol.core.DeviceQueries;
import dev.andre.homecontrol.core.UnsupportedActionException;
import dev.andre.homecontrol.core.playback.ContentItem;
import dev.andre.homecontrol.core.playback.DelegatedRoute;
import dev.andre.homecontrol.core.playback.DeviceRoute;
import dev.andre.homecontrol.core.playback.PlayableRef;
import dev.andre.homecontrol.core.playback.PlayableResolver;
import dev.andre.homecontrol.core.playback.PlaybackPlanner;
import dev.andre.homecontrol.core.playback.Route;
import dev.andre.homecontrol.core.playback.RouteExecutor;
import dev.andre.homecontrol.core.playback.UnroutableException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Resolve, plan, execute, report. Commands are ephemeral: a failure here is final. */
@Service
public class PlaybackService {

    private final DeviceQueries devices;
    private final DeviceCommands commands;
    private final PlaybackPlanner planner;
    private final List<PlayableResolver> resolvers;
    /** route key → the executor that runs it. */
    private final Map<String, RouteExecutor> executors;

    public PlaybackService(DeviceQueries devices, DeviceCommands commands, PlaybackPlanner planner) {
        this(devices, commands, planner, List.of(), List.of());
    }

    @Autowired
    public PlaybackService(DeviceQueries devices, DeviceCommands commands, PlaybackPlanner planner,
                           ObjectProvider<PlayableResolver> resolvers, ObjectProvider<RouteExecutor> executors) {
        this(devices, commands, planner, resolvers.orderedStream().toList(), executors.orderedStream().toList());
    }

    public PlaybackService(DeviceQueries devices, DeviceCommands commands, PlaybackPlanner planner,
                           List<PlayableResolver> resolvers, List<RouteExecutor> executors) {
        this.devices = devices;
        this.commands = commands;
        this.planner = planner;
        this.resolvers = List.copyOf(resolvers);
        this.executors = byKey(executors);
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
        List<Route> routes = preview.routes().stream().filter(route -> !skip.contains(route.key())).toList();
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
        if (route instanceof Route.Unroutable(var reason) && !resolved.notes().isEmpty()) {
            return new Route.Unroutable(String.join("; ", resolved.notes()) + "; " + reason);
        }
        return route;
    }

    /** The planner's own reason nothing routes, prefixed with any resolver notes — shared wording with {@link #plan}. */
    private String explain(Resolved resolved) {
        Route route = planner.plan(resolved.item(), resolved.capabilities());
        String reason = route instanceof Route.Unroutable(var routeReason) ? routeReason : "no route";
        return resolved.notes().isEmpty() ? reason : String.join("; ", resolved.notes()) + "; " + reason;
    }

    private void execute(Route route, Device device) {
        switch (route) {
            case DeviceRoute deviceRoute -> commands.execute(device.id(), deviceRoute.action());
            case DelegatedRoute delegated -> Optional.ofNullable(executors.get(delegated.key()))
                    .orElseThrow(() -> new UnroutableException(
                            device.name() + ": " + delegated.source() + " is switched off on this server"))
                    .execute(delegated, device);
            case Route.Unroutable(var reason) -> throw new UnroutableException(device.name() + ": " + reason);
        }
    }

    /** Each executor under every key it runs; two executors claiming one key is a wiring mistake. */
    private static Map<String, RouteExecutor> byKey(List<RouteExecutor> executors) {
        Map<String, RouteExecutor> byKey = new HashMap<>();
        for (RouteExecutor executor : executors) {
            for (String key : executor.keys()) {
                if (byKey.putIfAbsent(key, executor) != null) {
                    throw new IllegalStateException("Two route executors claim " + key);
                }
            }
        }
        return Map.copyOf(byKey);
    }

    private Device device(String deviceId) {
        return devices.device(deviceId)
                .orElseThrow(() -> new DeviceNotFoundException("No device with id " + deviceId));
    }
}

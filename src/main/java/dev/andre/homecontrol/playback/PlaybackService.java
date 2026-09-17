package dev.andre.homecontrol.playback;

import dev.andre.homecontrol.core.Capability;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceNotFoundException;
import dev.andre.homecontrol.core.playback.ContentItem;
import dev.andre.homecontrol.core.playback.PlayableRef;
import dev.andre.homecontrol.core.playback.PlayableResolver;
import dev.andre.homecontrol.core.playback.PlaybackPlanner;
import dev.andre.homecontrol.core.playback.Route;
import dev.andre.homecontrol.core.playback.RouteExecutor;
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
        switch (route) {
            case Route.OpenAppLink open -> devices.execute(deviceId, open.action());
            case Route.Cast cast -> devices.execute(deviceId, cast.action());
            case Route.CastMessage message -> devices.execute(deviceId, message.action());
            case Route.JellyfinSession session -> executors.stream()
                    .filter(executor -> executor.executes(session))
                    .findFirst()
                    .orElseThrow(() -> new UnroutableException(device.name() + ": Jellyfin is switched off on this server"))
                    .execute(session, device);
            case Route.Unroutable unroutable -> throw new UnroutableException(device.name() + ": " + unroutable.reason());
        }
        return route;
    }

    private Route plan(ContentItem item, Device device) {
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
        if (playables.isEmpty() && !notes.isEmpty()) {
            return new Route.Unroutable(String.join("; ", notes));
        }
        Route route = planner.plan(item.withPlayables(playables), capabilities);
        if (route instanceof Route.Unroutable unroutable && !notes.isEmpty()) {
            return new Route.Unroutable(String.join("; ", notes) + "; " + unroutable.reason());
        }
        return route;
    }

    private Device device(String deviceId) {
        return devices.device(deviceId)
                .orElseThrow(() -> new DeviceNotFoundException("No device with id " + deviceId));
    }
}

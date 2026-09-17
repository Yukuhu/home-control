package dev.andre.homecontrol.adapters.sonos;

import dev.andre.homecontrol.adapters.sonos.protocol.SonosActions;
import dev.andre.homecontrol.adapters.sonos.protocol.SonosEndpoints;
import dev.andre.homecontrol.adapters.sonos.protocol.SonosUris;
import dev.andre.homecontrol.adapters.sonos.protocol.ZoneGroupState;
import dev.andre.homecontrol.adapters.upnp.protocol.NowPlayings;
import dev.andre.homecontrol.adapters.upnp.protocol.PlayedItem;
import dev.andre.homecontrol.adapters.upnp.protocol.PositionInfo;
import dev.andre.homecontrol.adapters.upnp.protocol.ProtocolInfo;
import dev.andre.homecontrol.adapters.upnp.protocol.ReconnectingPoller;
import dev.andre.homecontrol.adapters.upnp.protocol.RendererCommands;
import dev.andre.homecontrol.adapters.upnp.protocol.RendererFaultException;
import dev.andre.homecontrol.adapters.upnp.protocol.ServiceEndpoint;
import dev.andre.homecontrol.adapters.upnp.protocol.SoapClient;
import dev.andre.homecontrol.adapters.upnp.protocol.SoapFault;
import dev.andre.homecontrol.adapters.upnp.protocol.TransportInfo;
import dev.andre.homecontrol.adapters.upnp.protocol.UpnpActions;
import dev.andre.homecontrol.adapters.upnp.protocol.VolumeReading;
import dev.andre.homecontrol.core.Action;
import dev.andre.homecontrol.core.ActionFailedException;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceHandle;
import dev.andre.homecontrol.core.DeviceOfflineException;
import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.DeviceStatus;
import dev.andre.homecontrol.core.GroupListing;
import dev.andre.homecontrol.core.GroupMember;
import dev.andre.homecontrol.core.NowPlaying;
import dev.andre.homecontrol.core.SpeakerGroup;
import dev.andre.homecontrol.core.SpeakerTopology;
import dev.andre.homecontrol.core.UnsupportedActionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static dev.andre.homecontrol.adapters.sonos.protocol.SonosEndpoints.AV_TRANSPORT;
import static dev.andre.homecontrol.adapters.sonos.protocol.SonosEndpoints.AV_TRANSPORT_PATH;

/**
 * One Sonos room: transport through its group coordinator, volume on itself, grouping controls. Every
 * call goes through the capped {@link SoapClient}: to the registered address, or to a coordinator the
 * topology names, which {@link ZoneGroupState} only keeps at a private IP literal.
 */
public class SonosSession implements DeviceHandle, GroupListing {

    private static final Logger log = LoggerFactory.getLogger(SonosSession.class);
    /** Sonos: "Command not supported or not a coordinator". */
    private static final int NOT_COORDINATOR = 800;

    private final Device device;
    private final SonosSettings settings;
    private final SonosProperties properties;
    private final SoapClient soap;
    private final RendererCommands commands;
    private final Consumer<DeviceState> onChange;
    private final Runnable onClosed;
    private final Clock clock;
    private final ReconnectingPoller poller;

    private volatile ZoneGroupState topology;
    private volatile Instant topologyReadAt = Instant.EPOCH;
    private volatile ProtocolInfo sink = ProtocolInfo.UNKNOWN;
    private volatile TransportInfo transport = TransportInfo.NONE;
    private volatile PlayedItem lastPlayed;
    private volatile DeviceState state = DeviceState.initial();
    /** True from a completed connect until a disconnect or close; commands and topology need it. */
    private volatile boolean live;

    public SonosSession(Device device, SonosProperties properties, HttpClient http,
                        Consumer<DeviceState> onChange, Runnable onClosed) {
        this(device, properties, http, onChange, onClosed, Clock.systemUTC());
    }

    SonosSession(Device device, SonosProperties properties, HttpClient http,
                 Consumer<DeviceState> onChange, Runnable onClosed, Clock clock) {
        this.device = device;
        this.settings = SonosSettings.of(device);
        this.properties = properties;
        this.soap = new SoapClient(http, Duration.ofSeconds(properties.commandTimeoutSeconds()));
        this.commands = new RendererCommands(soap, device.name());
        this.onChange = onChange;
        this.onClosed = onClosed;
        this.clock = clock;
        this.poller = new ReconnectingPoller("sonos-" + device.id(),
                Duration.ofSeconds(properties.reconnectInitialDelaySeconds()),
                Duration.ofSeconds(properties.reconnectMaxDelaySeconds()), new Link());
    }

    public void start() {
        onChange.accept(state);
        poller.start();
    }

    public String uuid() {
        return settings.uuid();
    }

    public void reconnectNow() {
        poller.reconnectNow();
    }

    @Override
    public DeviceState state() {
        return state;
    }

    @Override
    public Optional<SpeakerTopology> speakerTopology() {
        ZoneGroupState current = topology;
        if (current == null || !live) {
            return Optional.empty();
        }
        List<SpeakerGroup> groups = current.groups().stream()
                .map(group -> new SpeakerGroup(group.coordinator(), group.visibleMembers().stream()
                        .sorted(Comparator.comparing(member -> !member.uuid().equals(group.coordinator())))
                        .map(member -> new GroupMember(member.uuid(), member.zoneName()))
                        .toList()))
                .filter(group -> !group.members().isEmpty())
                .toList();
        return Optional.of(new SpeakerTopology(settings.uuid(), groups));
    }

    @Override
    public void execute(Action action) {
        if (!live) {
            throw new DeviceOfflineException(device.name() + " is not connected");
        }
        try {
            switch (action) {
                case Action.PlayMedia play -> {
                    Action.PlayMedia forSonos = new Action.PlayMedia(SonosUris.forPlayback(play.url(), play.mimeType()),
                            play.mimeType(), play.title(), play.subtitle());
                    onCoordinator(coordinator -> commands.playUri(coordinator, sink, forSonos, "*"));
                    lastPlayed = new PlayedItem(forSonos.url().toString(), play.title());
                }
                case Action.Pause ignored -> onCoordinator(coordinator ->
                        commands.transport(coordinator, UpnpActions.pause(AV_TRANSPORT), "pause"));
                case Action.Resume ignored -> onCoordinator(coordinator ->
                        commands.transport(coordinator, UpnpActions.play(AV_TRANSPORT), "resume playback"));
                case Action.Stop ignored -> onCoordinator(coordinator ->
                        commands.transport(coordinator, UpnpActions.stop(AV_TRANSPORT), "stop playback"));
                case Action.SetVolume volume -> commands.setVolume(renderingControl(), volume.level(), 100);
                case Action.Mute mute -> commands.setMute(renderingControl(), mute.muted());
                case Action.JoinGroup join -> join(join.memberId());
                case Action.LeaveGroup ignored -> leave();
                case Action.PressKey ignored -> throw unsupported("has no remote keys");
                case Action.OpenAppLink ignored -> throw unsupported("cannot open app links");
                case Action.SelectInput ignored -> throw unsupported("has no inputs");
                case Action.CastLoad ignored -> throw unsupported("is not a Cast receiver");
                case Action.CastMessage ignored -> throw unsupported("is not a Cast receiver");
            }
        } finally {
            poller.pollNow();
        }
    }

    private void join(String memberId) {
        ZoneGroupState current = commands.run("look up the speaker groups", this::readTopology);
        ZoneGroupState.Group target = current.groupOf(memberId)
                .orElseThrow(() -> new ActionFailedException(device.name() + " cannot find that speaker; it may have left the network"));
        if (target.contains(settings.uuid())) {
            return;
        }
        String name = target.coordinatorMember().map(ZoneGroupState.Member::zoneName).orElse("that group");
        commands.transport(own(AV_TRANSPORT_PATH, AV_TRANSPORT),
                UpnpActions.setAvTransportUri(AV_TRANSPORT, SonosUris.groupWith(target.coordinator()), ""), "join " + name);
        topologyReadAt = Instant.EPOCH;
    }

    private void leave() {
        ZoneGroupState current = commands.run("look up the speaker groups", this::readTopology);
        boolean alone = current.groupOf(settings.uuid()).map(group -> group.visibleMembers().size() <= 1).orElse(true);
        if (alone) {
            return;
        }
        commands.transport(own(AV_TRANSPORT_PATH, AV_TRANSPORT),
                UpnpActions.becomeCoordinatorOfStandaloneGroup(AV_TRANSPORT), "leave the group");
        topologyReadAt = Instant.EPOCH;
    }

    private ZoneGroupState readTopology() throws IOException, SoapFault {
        String xml = soap.call(own(SonosEndpoints.ZONE_GROUP_TOPOLOGY_PATH, SonosEndpoints.ZONE_GROUP_TOPOLOGY).controlUrl(),
                SonosActions.getZoneGroupState()).getOrDefault("ZoneGroupState", "");
        try {
            ZoneGroupState parsed = ZoneGroupState.parse(xml, SonosEndpoints.isLoopback(device.host()));
            topology = parsed;
            topologyReadAt = clock.instant();
            return parsed;
        } catch (IllegalArgumentException e) {
            throw new SoapFault(0, "Unreadable zone group state");
        }
    }

    /**
     * Runs a transport command on the coordinator. Sonos answers 800 when the target is no longer the
     * coordinator (grouping changed in the Sonos app since the last topology read): re-read once and retry.
     */
    private void onCoordinator(Consumer<ServiceEndpoint> command) {
        try {
            command.accept(coordinatorAvTransport());
        } catch (RendererFaultException fault) {
            if (fault.errorCode() != NOT_COORDINATOR) {
                throw fault;
            }
            commands.run("look up the speaker groups", this::readTopology);
            command.accept(coordinatorAvTransport());
        }
    }

    /** Transport commands must reach the group coordinator; a standalone room coordinates itself. */
    private ServiceEndpoint coordinatorAvTransport() {
        ZoneGroupState current = topology;
        if (current != null) {
            Optional<ZoneGroupState.Member> coordinator = current.groupOf(settings.uuid())
                    .flatMap(ZoneGroupState.Group::coordinatorMember)
                    .filter(member -> !member.uuid().equals(settings.uuid()));
            if (coordinator.isPresent()) {
                return SonosEndpoints.endpoint(coordinator.get().host(), coordinator.get().port(), AV_TRANSPORT_PATH, AV_TRANSPORT);
            }
        }
        return own(AV_TRANSPORT_PATH, AV_TRANSPORT);
    }

    private ServiceEndpoint renderingControl() {
        return own(SonosEndpoints.RENDERING_CONTROL_PATH, SonosEndpoints.RENDERING_CONTROL);
    }

    private ServiceEndpoint own(String path, String serviceType) {
        return SonosEndpoints.endpoint(device.host(), settings.port(), path, serviceType);
    }

    private UnsupportedActionException unsupported(String what) {
        return new UnsupportedActionException(device.name() + " is a Sonos speaker and " + what);
    }

    private void readState() throws IOException, SoapFault {
        ServiceEndpoint coordinator = coordinatorAvTransport();
        TransportInfo info = commands.transportInfo(coordinator);
        VolumeReading volume = commands.volume(renderingControl(), 100);
        transport = info;
        NowPlaying nowPlaying = null;
        if (info.active()) {
            // A grouped room shows its coordinator's track; its title comes from the coordinator's metadata.
            PositionInfo position;
            try {
                position = commands.positionInfo(coordinator);
            } catch (SoapFault fault) {
                position = new PositionInfo("", "", null, null);
            }
            nowPlaying = NowPlayings.of(info, position, lastPlayed);
        }
        publish(state.withStatus(DeviceStatus.CONNECTED).withPower(true).withVolume(volume.percent(), 100, volume.muted())
                .withNowPlaying(nowPlaying));
    }

    private synchronized void publish(DeviceState next) {
        DeviceState previous = state;
        state = next;
        if (!next.sameIgnoringTime(previous)) {
            try {
                onChange.accept(next);
            } catch (RuntimeException e) {
                log.warn("A device state listener failed for {}", device.id(), e);
            }
        }
    }

    @Override
    public void close() {
        live = false;
        poller.close();
        onClosed.run();
    }

    private final class Link implements ReconnectingPoller.Link {

        @Override
        public void connect() throws Exception {
            readTopology();
            try {
                sink = commands.sink(own(SonosEndpoints.CONNECTION_MANAGER_PATH, SonosEndpoints.CONNECTION_MANAGER));
            } catch (SoapFault fault) {
                sink = ProtocolInfo.UNKNOWN;
            }
            readState();
            live = true;
        }

        @Override
        public void poll() throws Exception {
            if (Duration.between(topologyReadAt, clock.instant()).toSeconds() >= properties.topologyIntervalSeconds()) {
                try {
                    readTopology();
                } catch (SoapFault fault) {
                    log.debug("{}: topology unavailable: {}", device.id(), fault.getMessage());
                }
            }
            readState();
        }

        @Override
        public Duration nextPollDelay() {
            return Duration.ofSeconds(transport.active() ? properties.pollIntervalSeconds() : properties.idlePollIntervalSeconds());
        }

        @Override
        public void disconnected(Exception cause) {
            live = false;
            transport = TransportInfo.NONE;
            log.debug("Sonos player {} unreachable: {}", device.id(), cause.getMessage());
            publish(state.withStatus(DeviceStatus.DISCONNECTED).withNowPlaying(null));
        }
    }
}

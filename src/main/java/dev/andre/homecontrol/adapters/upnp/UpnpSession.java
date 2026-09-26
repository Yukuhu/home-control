package dev.andre.homecontrol.adapters.upnp;

import dev.andre.homecontrol.adapters.upnp.protocol.DidlLite;
import dev.andre.homecontrol.adapters.upnp.protocol.NowPlayings;
import dev.andre.homecontrol.adapters.upnp.protocol.PlayedItem;
import dev.andre.homecontrol.adapters.upnp.protocol.PositionInfo;
import dev.andre.homecontrol.adapters.upnp.protocol.ProtocolInfo;
import dev.andre.homecontrol.adapters.upnp.protocol.ReconnectingPoller;
import dev.andre.homecontrol.adapters.upnp.protocol.RendererCommands;
import dev.andre.homecontrol.adapters.upnp.protocol.ServiceEndpoint;
import dev.andre.homecontrol.adapters.upnp.protocol.SoapClient;
import dev.andre.homecontrol.adapters.upnp.protocol.SoapFault;
import dev.andre.homecontrol.adapters.upnp.protocol.TransportInfo;
import dev.andre.homecontrol.adapters.upnp.protocol.UpnpActions;
import dev.andre.homecontrol.adapters.upnp.protocol.VolumeRange;
import dev.andre.homecontrol.adapters.upnp.protocol.VolumeReading;
import dev.andre.homecontrol.core.Action;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceHandle;
import dev.andre.homecontrol.core.DeviceOfflineException;
import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.DeviceStatus;
import dev.andre.homecontrol.core.NowPlaying;
import dev.andre.homecontrol.core.UnsupportedActionException;
import dev.andre.homecontrol.discovery.ssdp.DeviceDescription;
import dev.andre.homecontrol.discovery.ssdp.DeviceDescriptions;
import dev.andre.homecontrol.discovery.ssdp.DeviceFetch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

/** One registered UPnP/DLNA renderer: resolves its services, polls its state, plays URLs. */
public class UpnpSession implements DeviceHandle {

    private static final Logger log = LoggerFactory.getLogger(UpnpSession.class);

    /** Resolved services; {@code renderingControl} may be null. */
    record Endpoints(ServiceEndpoint avTransport, ServiceEndpoint renderingControl, int volumeMax, ProtocolInfo sink) {
    }

    private final Device device;
    private final UpnpSettings settings;
    private final UpnpProperties properties;
    private final HttpClient http;
    private final RendererCommands commands;
    private final Function<String, Optional<URI>> locator;
    private final Consumer<DeviceState> onChange;
    private final Runnable onClosed;
    private final ReconnectingPoller poller;

    private volatile Endpoints endpoints;
    private volatile TransportInfo transport = TransportInfo.NONE;
    private volatile PlayedItem lastPlayed;
    private volatile DeviceState state = DeviceState.initial();

    public UpnpSession(Device device, UpnpProperties properties, HttpClient http,
                       Function<String, Optional<URI>> locator, Consumer<DeviceState> onChange, Runnable onClosed) {
        this.device = device;
        this.settings = UpnpSettings.of(device);
        this.properties = properties;
        this.http = http;
        this.commands = new RendererCommands(new SoapClient(http, Duration.ofSeconds(properties.commandTimeoutSeconds())), device.name());
        this.locator = locator;
        this.onChange = onChange;
        this.onClosed = onClosed;
        this.poller = new ReconnectingPoller("upnp-" + device.id(),
                Duration.ofSeconds(properties.reconnectInitialDelaySeconds()),
                Duration.ofSeconds(properties.reconnectMaxDelaySeconds()), new Link());
    }

    public void start() {
        onChange.accept(state);
        poller.start();
    }

    public String udn() {
        return settings.udn();
    }

    public void reconnectNow() {
        poller.reconnectNow();
    }

    @Override
    public DeviceState state() {
        return state;
    }

    @Override
    public void execute(Action action) {
        Endpoints current = endpoints;
        if (current == null) { // resolved endpoints exist only while connected
            throw new DeviceOfflineException(device.name() + " is not connected");
        }
        String av = current.avTransport().serviceType();
        try {
            switch (action) {
                case Action.PlayMedia play -> {
                    commands.playUri(current.avTransport(), current.sink(), play, DidlLite.DLNA_STREAMING);
                    lastPlayed = new PlayedItem(play.url().toString(), play.title());
                }
                case Action.Pause _ -> commands.transport(current.avTransport(), UpnpActions.pause(av), "pause");
                case Action.Resume _ -> commands.transport(current.avTransport(), UpnpActions.play(av), "resume playback");
                case Action.Stop _ -> commands.transport(current.avTransport(), UpnpActions.stop(av), "stop playback");
                case Action.SetVolume(var level) -> commands.setVolume(volumeControl(current), level, current.volumeMax());
                case Action.Mute(var muted) -> commands.setMute(volumeControl(current), muted);
                case Action.PressKey _ -> throw unsupported("has no remote keys");
                case Action.OpenAppLink _ -> throw unsupported("cannot open app links");
                case Action.SelectInput _ -> throw unsupported("has no inputs");
                case Action.CastLoad _ -> throw unsupported("is not a Cast receiver");
                case Action.CastMessage _ -> throw unsupported("is not a Cast receiver");
                case Action.JoinGroup _ -> throw unsupported("cannot be grouped");
                case Action.LeaveGroup _ -> throw unsupported("cannot be grouped");
            }
        } finally {
            poller.pollNow();
        }
    }

    private ServiceEndpoint volumeControl(Endpoints current) {
        if (current.renderingControl() == null) {
            throw unsupported("has no volume control");
        }
        return current.renderingControl();
    }

    private UnsupportedActionException unsupported(String what) {
        return new UnsupportedActionException(device.name() + " is a media renderer and " + what);
    }

    /** Services on another host than the (already verified) description location are refused (epic constraint). */
    private Optional<ServiceEndpoint> service(DeviceDescription description, String typePrefix, URI location) {
        return description.service(typePrefix).map(ServiceEndpoint::of).filter(endpoint -> {
            boolean sameHost = onHost(endpoint.controlUrl(), location);
            if (!sameHost) {
                log.warn("Ignoring {} of {}: its control URL is not on the host it announced itself from", typePrefix, device.id());
            }
            boolean validType = SoapClient.isValidServiceType(endpoint.serviceType());
            if (!validType) {
                log.warn("Ignoring {} of {}: malformed service type", typePrefix, device.id());
            }
            return sameHost && validType;
        });
    }

    private static boolean onHost(URI url, URI location) {
        return url != null && "http".equalsIgnoreCase(url.getScheme()) && url.getHost() != null
                && url.getHost().equalsIgnoreCase(location.getHost());
    }

    private int volumeMaximum(ServiceEndpoint renderingControl, URI location, Duration timeout) {
        URI scpd = renderingControl.scpdUrl();
        if (!onHost(scpd, location)) {
            return VolumeRange.DEFAULT_MAXIMUM;
        }
        try {
            return VolumeRange.maximum(DeviceFetch.get(http, scpd, timeout, DeviceFetch.MAX_DESCRIPTION_BYTES));
        } catch (IOException _) {
            return VolumeRange.DEFAULT_MAXIMUM;
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return VolumeRange.DEFAULT_MAXIMUM;
        }
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
        poller.close();
        endpoints = null;
        onClosed.run();
    }

    private final class Link implements ReconnectingPoller.Link {

        /**
         * Reads the description and SCPD only under F1's rules ({@link DeviceFetch}): the location
         * announced for this UDN, else the stored one — either way on the registered device's own address;
         * plain HTTP to an IP literal, 64 KiB at most, no redirects — and only a description that names
         * this device's UDN. Locations are never logged.
         */
        private Endpoints resolve() throws IOException, InterruptedException {
            Optional<URI> announced = Optional.ofNullable(settings.udn()).flatMap(locator);
            URI location = announced.orElse(settings.location());
            // Whatever the source, the description must live on the registered device's address: an
            // announcement cannot move this session (and the stream URLs it sends) to another host.
            if (location == null || !DeviceFetch.isSafeToFetch(location, device.host())) {
                throw new IOException(device.id() + " has no description address on its own host");
            }
            Duration timeout = Duration.ofSeconds(properties.commandTimeoutSeconds());
            DeviceDescription description;
            try {
                description = DeviceDescriptions.parse(
                        DeviceFetch.get(http, location, timeout, DeviceFetch.MAX_DESCRIPTION_BYTES), location);
            } catch (IllegalArgumentException _) {
                throw new IOException("Unreadable description for " + device.id());
            }
            if (settings.udn() != null && (description.udn() == null || !settings.udn().equalsIgnoreCase(description.udn()))) {
                throw new IOException("The description at " + device.id() + "'s address belongs to another device");
            }
            ServiceEndpoint avTransport = service(description, UpnpActions.AV_TRANSPORT, location)
                    .orElseThrow(() -> new IOException(device.id() + " offers no usable AVTransport service"));
            ServiceEndpoint renderingControl = service(description, UpnpActions.RENDERING_CONTROL, location).orElse(null);
            ServiceEndpoint connectionManager = service(description, UpnpActions.CONNECTION_MANAGER, location).orElse(null);
            int volumeMax = renderingControl == null ? 0 : volumeMaximum(renderingControl, location, timeout);
            ProtocolInfo sink = ProtocolInfo.UNKNOWN;
            if (connectionManager != null) {
                try {
                    sink = commands.sink(connectionManager);
                } catch (SoapFault fault) {
                    log.debug("{} did not list its formats: {}", device.id(), fault.getMessage());
                }
            }
            return new Endpoints(avTransport, renderingControl, volumeMax, sink);
        }

        /** Reads the device and publishes; runs on the poll loop only. */
        private void readState(Endpoints current) throws IOException, SoapFault {
            TransportInfo info = commands.transportInfo(current.avTransport());
            transport = info;
            NowPlaying nowPlaying = null;
            if (info.active()) {
                PositionInfo position;
                try {
                    position = commands.positionInfo(current.avTransport());
                } catch (SoapFault _) {
                    position = new PositionInfo("", "", null, null);
                }
                nowPlaying = NowPlayings.of(info, position, lastPlayed);
            }
            DeviceState next = state.withStatus(DeviceStatus.CONNECTED).withPower(true).withNowPlaying(nowPlaying);
            if (current.renderingControl() != null) {
                try {
                    VolumeReading volume = commands.volume(current.renderingControl(), current.volumeMax());
                    next = next.withVolume(volume.percent(), 100, volume.muted());
                } catch (SoapFault fault) {
                    log.debug("{} did not report its volume: {}", device.id(), fault.getMessage());
                }
            }
            publish(next);
        }

        @Override
        public void connect() throws Exception {
            Endpoints resolved = resolve();
            endpoints = resolved;
            try {
                readState(resolved);
            } catch (Exception e) {
                endpoints = null;
                throw e;
            }
        }

        @Override
        public void poll() throws Exception {
            Endpoints current = endpoints;
            if (current != null) {
                readState(current);
            }
        }

        @Override
        public Duration nextPollDelay() {
            return Duration.ofSeconds(transport.active() ? properties.pollIntervalSeconds() : properties.idlePollIntervalSeconds());
        }

        @Override
        public void disconnected(Exception cause) {
            endpoints = null;
            transport = TransportInfo.NONE;
            log.debug("Media renderer {} unreachable: {}", device.id(), cause.getMessage());
            publish(state.withStatus(DeviceStatus.DISCONNECTED).withNowPlaying(null));
        }
    }
}

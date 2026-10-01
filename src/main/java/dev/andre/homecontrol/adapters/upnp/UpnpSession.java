package dev.andre.homecontrol.adapters.upnp;

import dev.andre.homecontrol.adapters.support.DeviceCalls;
import dev.andre.homecontrol.adapters.support.ReconnectingPoller;
import dev.andre.homecontrol.adapters.upnp.protocol.DidlLite;
import dev.andre.homecontrol.adapters.upnp.protocol.PlayedItem;
import dev.andre.homecontrol.adapters.upnp.protocol.ProtocolInfo;
import dev.andre.homecontrol.adapters.support.RendererCommands;
import dev.andre.homecontrol.adapters.support.RendererStatePoller;
import dev.andre.homecontrol.adapters.support.StatePublisher;
import dev.andre.homecontrol.adapters.upnp.protocol.RendererResolver;
import dev.andre.homecontrol.adapters.upnp.protocol.ServiceEndpoint;
import dev.andre.homecontrol.adapters.upnp.protocol.SoapClient;
import dev.andre.homecontrol.adapters.upnp.protocol.SoapFault;
import dev.andre.homecontrol.adapters.upnp.protocol.UpnpActions;
import dev.andre.homecontrol.core.Action;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceHandle;
import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.UnsupportedActionException;
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
    private final RendererResolver resolver;
    private final RendererCommands commands;
    private final Function<String, Optional<URI>> locator;
    private final Runnable onClosed;
    private final ReconnectingPoller poller;
    private final StatePublisher publisher;
    private final RendererStatePoller renderer;

    // Immutable record replaced wholesale by the poll loop (and cleared by close()); command threads only read it.
    @SuppressWarnings("java:S3077")
    private volatile Endpoints endpoints;
    /** After close() nothing reopens the session, not even a connect that finishes late. */
    private volatile boolean closed;

    public UpnpSession(Device device, UpnpProperties properties, HttpClient http,
                       Function<String, Optional<URI>> locator, Consumer<DeviceState> onChange, Runnable onClosed) {
        this(device, UpnpTimings.from(properties), http, locator, onChange, onClosed);
    }

    UpnpSession(Device device, UpnpTimings timings, HttpClient http,
               Function<String, Optional<URI>> locator, Consumer<DeviceState> onChange, Runnable onClosed) {
        this.device = device;
        this.settings = UpnpSettings.of(device);
        this.resolver = new RendererResolver(http, timings.commandTimeout());
        this.commands = new RendererCommands(new SoapClient(http, timings.commandTimeout()), device.name());
        this.locator = locator;
        this.publisher = new StatePublisher(device.id(), DeviceState.initial(), onChange);
        this.renderer = new RendererStatePoller(device.id(), commands, publisher, timings.pollInterval(),
                timings.idlePollInterval());
        this.onClosed = onClosed;
        this.poller = new ReconnectingPoller("upnp-" + device.id(),
                timings.reconnectInitialDelay(), timings.reconnectMaxDelay(), new Link());
    }

    public void start() {
        publisher.announce();
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
        return publisher.current();
    }

    @Override
    public void execute(Action action) {
        Endpoints current = endpoints;
        if (closed || current == null) { // resolved endpoints exist only while connected
            throw DeviceCalls.notConnected(device.name());
        }
        String av = current.avTransport().serviceType();
        try {
            switch (action) {
                case Action.PlayMedia play -> {
                    commands.playUri(current.avTransport(), current.sink(), play, DidlLite.DLNA_STREAMING);
                    renderer.played(new PlayedItem(play.url().toString(), play.title()));
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

    @Override
    public void close() {
        closed = true;
        publisher.close();
        poller.close();
        endpoints = null;
        onClosed.run();
    }

    /** Every callback runs on the poll loop's thread. */
    private final class Link implements ReconnectingPoller.Link {

        /** The announced location for this UDN, else the stored one; {@link RendererResolver} checks it. */
        private Endpoints resolve() throws IOException {
            Optional<URI> announced = Optional.ofNullable(settings.udn()).flatMap(locator);
            // Whatever the source, the description must live on the registered device's address: an
            // announcement cannot move this session (and the stream URLs it sends) to another host.
            RendererResolver.Renderer found = resolver.resolve(announced.orElse(settings.location()), device.host(),
                    settings.udn());
            ProtocolInfo sink = ProtocolInfo.UNKNOWN;
            if (found.connectionManager() != null) {
                try {
                    sink = commands.sink(found.connectionManager());
                } catch (SoapFault fault) {
                    log.debug("{} did not list its formats: {}", device.id(), fault.getMessage());
                }
            }
            return new Endpoints(found.avTransport(), found.renderingControl(), found.volumeMax(), sink);
        }

        /** Reads the device and publishes; runs on the poll loop only. */
        private void readState(Endpoints current) throws IOException, SoapFault {
            renderer.read(new RendererStatePoller.Endpoints(current.avTransport(), current.renderingControl(),
                    current.volumeMax(), false));
        }

        @Override
        public void connect() throws IOException {
            Endpoints resolved = resolve();
            endpoints = resolved;
            try {
                readState(resolved);
            } catch (SoapFault fault) {
                endpoints = null;
                throw new IOException(device.id() + " refused to report its state: " + fault.getMessage());
            } catch (IOException | RuntimeException e) {
                endpoints = null;
                throw e;
            }
        }

        @Override
        public void poll() throws IOException {
            Endpoints current = endpoints;
            if (current != null) {
                try {
                    readState(current);
                } catch (SoapFault fault) {
                    log.debug("{}: poll failed: {}", device.id(), fault.getMessage());
                }
            }
        }

        @Override
        public Duration nextPollDelay() {
            return renderer.nextPollDelay();
        }

        @Override
        public void disconnected(Exception cause) {
            endpoints = null;
            log.debug("Media renderer {} unreachable: {}", device.id(), cause.getMessage());
            renderer.lost();
        }
    }
}

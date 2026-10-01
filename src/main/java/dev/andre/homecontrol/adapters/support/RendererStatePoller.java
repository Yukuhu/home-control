package dev.andre.homecontrol.adapters.support;

import dev.andre.homecontrol.adapters.upnp.protocol.PlayedItem;
import dev.andre.homecontrol.adapters.upnp.protocol.PositionInfo;
import dev.andre.homecontrol.adapters.upnp.protocol.ServiceEndpoint;
import dev.andre.homecontrol.adapters.upnp.protocol.SoapFault;
import dev.andre.homecontrol.adapters.upnp.protocol.TransportInfo;
import dev.andre.homecontrol.adapters.upnp.protocol.VolumeReading;
import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.DeviceStatus;
import dev.andre.homecontrol.core.NowPlaying;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;

/**
 * Reads a UPnP renderer's transport, position and volume and publishes them; shared by Sonos and UPnP. {@link #read},
 * {@link #nextPollDelay} and {@link #lost} run on the session's poll loop; {@link #played} on the command thread that
 * started a stream.
 */
public final class RendererStatePoller {

    /** One read's services; {@code renderingControl} may be null; a required volume fails the read when it faults. */
    public record Endpoints(ServiceEndpoint avTransport, ServiceEndpoint renderingControl, int volumeMax,
                            boolean volumeRequired) {
    }

    private static final Logger log = LoggerFactory.getLogger(RendererStatePoller.class);

    private final String deviceId;
    private final RendererCommands commands;
    private final StatePublisher publisher;
    private final Duration pollInterval;
    private final Duration idlePollInterval;
    /** Poll loop only: chooses the next poll delay. */
    private TransportInfo transport = TransportInfo.NONE;
    // Immutable record written by the command thread that played it; the poll loop only reads it.
    @SuppressWarnings("java:S3077")
    private volatile PlayedItem lastPlayed;

    public RendererStatePoller(String deviceId, RendererCommands commands, StatePublisher publisher,
                               Duration pollInterval, Duration idlePollInterval) {
        this.deviceId = deviceId;
        this.commands = commands;
        this.publisher = publisher;
        this.pollInterval = pollInterval;
        this.idlePollInterval = idlePollInterval;
    }

    /** What the session just started, so a renderer that forgets the metadata still shows its title. */
    public void played(PlayedItem item) {
        lastPlayed = item;
    }

    public void read(Endpoints endpoints) throws IOException, SoapFault {
        TransportInfo info = commands.transportInfo(endpoints.avTransport());
        transport = info;
        NowPlaying nowPlaying = null;
        if (info.active()) {
            PositionInfo position;
            try {
                position = commands.positionInfo(endpoints.avTransport());
            } catch (SoapFault _) {
                position = new PositionInfo("", "", null, null);
            }
            nowPlaying = NowPlayings.of(info, position, lastPlayed);
        }
        DeviceState next = publisher.current().withStatus(DeviceStatus.CONNECTED).withPower(true)
                .withNowPlaying(nowPlaying);
        if (endpoints.renderingControl() != null) {
            try {
                VolumeReading volume = commands.volume(endpoints.renderingControl(), endpoints.volumeMax());
                next = next.withVolume(volume.percent(), 100, volume.muted());
            } catch (SoapFault fault) {
                if (endpoints.volumeRequired()) {
                    throw fault;
                }
                log.debug("{} did not report its volume: {}", deviceId, fault.getMessage());
            }
        }
        publisher.publish(next);
    }

    public Duration nextPollDelay() {
        return transport.active() ? pollInterval : idlePollInterval;
    }

    /** The renderer is gone: publish DISCONNECTED, nothing playing. */
    public void lost() {
        transport = TransportInfo.NONE;
        publisher.update(state -> state.withStatus(DeviceStatus.DISCONNECTED).withNowPlaying(null));
    }
}

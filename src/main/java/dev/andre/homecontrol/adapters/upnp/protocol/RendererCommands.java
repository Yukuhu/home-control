package dev.andre.homecontrol.adapters.upnp.protocol;

import dev.andre.homecontrol.core.Action;
import dev.andre.homecontrol.core.ActionFailedException;
import dev.andre.homecontrol.core.DeviceOfflineException;
import dev.andre.homecontrol.core.UnsupportedActionException;

import java.io.IOException;

/** AVTransport / RenderingControl commands with the epic's error mapping; shared by UPnP and Sonos sessions. */
public final class RendererCommands {

    @FunctionalInterface
    public interface SoapCall<T> {
        T call() throws IOException, SoapFault;
    }

    private final SoapClient soap;
    private final String deviceName;

    public RendererCommands(SoapClient soap, String deviceName) {
        this.soap = soap;
        this.deviceName = deviceName;
    }

    public void playUri(ServiceEndpoint avTransport, ProtocolInfo sink, Action.PlayMedia play, String additionalInfo) {
        String format = sink.match(play.mimeType())
                .orElseThrow(() -> new UnsupportedActionException(deviceName + " cannot play " + play.mimeType()));
        String metadata = DidlLite.item(play.url(), format, play.title(), play.subtitle(), additionalInfo);
        SoapRequest load = UpnpActions.setAvTransportUri(avTransport.serviceType(), play.url().toString(), metadata);
        run("play the stream", () -> {
            try {
                soap.call(avTransport.controlUrl(), load);
            } catch (SoapFault fault) {
                if (fault.errorCode() != 701 && fault.errorCode() != 705) {
                    throw fault;
                }
                // Several renderers take a new URI only when stopped.
                try {
                    soap.call(avTransport.controlUrl(), UpnpActions.stop(avTransport.serviceType()));
                } catch (SoapFault ignored) {
                    // already stopped
                }
                soap.call(avTransport.controlUrl(), load);
            }
            return soap.call(avTransport.controlUrl(), UpnpActions.play(avTransport.serviceType()));
        });
    }

    public void transport(ServiceEndpoint endpoint, SoapRequest request, String what) {
        run(what, () -> soap.call(endpoint.controlUrl(), request));
    }

    public void setVolume(ServiceEndpoint renderingControl, int percent, int max) {
        run("set the volume", () -> soap.call(renderingControl.controlUrl(),
                UpnpActions.setVolume(renderingControl.serviceType(), VolumeRange.toDevice(percent, max))));
    }

    public void setMute(ServiceEndpoint renderingControl, boolean muted) {
        run(muted ? "mute" : "unmute", () -> soap.call(renderingControl.controlUrl(),
                UpnpActions.setMute(renderingControl.serviceType(), muted)));
    }

    public TransportInfo transportInfo(ServiceEndpoint avTransport) throws IOException, SoapFault {
        return TransportInfo.from(soap.call(avTransport.controlUrl(), UpnpActions.getTransportInfo(avTransport.serviceType())));
    }

    public VolumeReading volume(ServiceEndpoint renderingControl, int max) throws IOException, SoapFault {
        String volume = soap.call(renderingControl.controlUrl(), UpnpActions.getVolume(renderingControl.serviceType()))
                .getOrDefault("CurrentVolume", "0").strip();
        String mute = soap.call(renderingControl.controlUrl(), UpnpActions.getMute(renderingControl.serviceType()))
                .getOrDefault("CurrentMute", "0").strip();
        try {
            return new VolumeReading(VolumeRange.toPercent(Integer.parseInt(volume), max),
                    mute.equals("1") || mute.equalsIgnoreCase("true"));
        } catch (NumberFormatException e) {
            throw new SoapFault(0, "Unreadable volume");
        }
    }

    public PositionInfo positionInfo(ServiceEndpoint avTransport) throws IOException, SoapFault {
        return PositionInfo.from(soap.call(avTransport.controlUrl(), UpnpActions.getPositionInfo(avTransport.serviceType())));
    }

    public ProtocolInfo sink(ServiceEndpoint connectionManager) throws IOException, SoapFault {
        return ProtocolInfo.parseSink(soap.call(connectionManager.controlUrl(),
                UpnpActions.getProtocolInfo(connectionManager.serviceType())).getOrDefault("Sink", ""));
    }

    public <T> T run(String what, SoapCall<T> call) {
        try {
            return call.call();
        } catch (SoapFault fault) {
            throw new RendererFaultException(deviceName + " refused to " + what + " (" + fault.getMessage() + ")", fault.errorCode());
        } catch (SoapTimeoutException e) {
            throw new ActionFailedException(deviceName + " did not answer in time when asked to " + what);
        } catch (IOException e) {
            throw new DeviceOfflineException(deviceName + " could not be reached to " + what);
        }
    }
}

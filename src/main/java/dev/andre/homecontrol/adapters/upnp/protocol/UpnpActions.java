package dev.andre.homecontrol.adapters.upnp.protocol;

import java.util.LinkedHashMap;
import java.util.Map;

/** The UPnP actions this project sends, with arguments in template order. */
public final class UpnpActions {

    /** Service type prefixes (the version suffix is taken from the device description). */
    public static final String AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:";
    public static final String RENDERING_CONTROL = "urn:schemas-upnp-org:service:RenderingControl:";
    public static final String CONNECTION_MANAGER = "urn:schemas-upnp-org:service:ConnectionManager:";

    private UpnpActions() {
    }

    public static SoapRequest setAvTransportUri(String serviceType, String uri, String metadata) {
        return request(serviceType, "SetAVTransportURI", "InstanceID", "0", "CurrentURI", uri, "CurrentURIMetaData", metadata);
    }

    public static SoapRequest play(String serviceType) {
        return request(serviceType, "Play", "InstanceID", "0", "Speed", "1");
    }

    public static SoapRequest pause(String serviceType) {
        return request(serviceType, "Pause", "InstanceID", "0");
    }

    public static SoapRequest stop(String serviceType) {
        return request(serviceType, "Stop", "InstanceID", "0");
    }

    public static SoapRequest getTransportInfo(String serviceType) {
        return request(serviceType, "GetTransportInfo", "InstanceID", "0");
    }

    public static SoapRequest getPositionInfo(String serviceType) {
        return request(serviceType, "GetPositionInfo", "InstanceID", "0");
    }

    /** Sonos extension on AVTransport: leave the current group. */
    public static SoapRequest becomeCoordinatorOfStandaloneGroup(String serviceType) {
        return request(serviceType, "BecomeCoordinatorOfStandaloneGroup", "InstanceID", "0");
    }

    public static SoapRequest getVolume(String serviceType) {
        return request(serviceType, "GetVolume", "InstanceID", "0", "Channel", "Master");
    }

    public static SoapRequest setVolume(String serviceType, int deviceVolume) {
        return request(serviceType, "SetVolume", "InstanceID", "0", "Channel", "Master", "DesiredVolume", String.valueOf(deviceVolume));
    }

    public static SoapRequest getMute(String serviceType) {
        return request(serviceType, "GetMute", "InstanceID", "0", "Channel", "Master");
    }

    public static SoapRequest setMute(String serviceType, boolean muted) {
        return request(serviceType, "SetMute", "InstanceID", "0", "Channel", "Master", "DesiredMute", muted ? "1" : "0");
    }

    public static SoapRequest getProtocolInfo(String serviceType) {
        return request(serviceType, "GetProtocolInfo");
    }

    public static SoapRequest request(String serviceType, String action, String... namesAndValues) {
        Map<String, String> arguments = new LinkedHashMap<>();
        for (int i = 0; i + 1 < namesAndValues.length; i += 2) {
            arguments.put(namesAndValues[i], namesAndValues[i + 1] == null ? "" : namesAndValues[i + 1]);
        }
        return new SoapRequest(serviceType, action, arguments);
    }
}

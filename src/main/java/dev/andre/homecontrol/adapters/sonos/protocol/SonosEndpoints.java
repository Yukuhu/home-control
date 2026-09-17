package dev.andre.homecontrol.adapters.sonos.protocol;

import dev.andre.homecontrol.adapters.upnp.protocol.ServiceEndpoint;
import dev.andre.homecontrol.discovery.ssdp.DeviceFetch;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/** Fixed Sonos control paths (identical on S1 and S2 firmware; SoCo services.py). */
public final class SonosEndpoints {

    public static final int DEFAULT_PORT = 1400;
    public static final String AV_TRANSPORT_PATH = "/MediaRenderer/AVTransport/Control";
    public static final String RENDERING_CONTROL_PATH = "/MediaRenderer/RenderingControl/Control";
    public static final String CONNECTION_MANAGER_PATH = "/MediaRenderer/ConnectionManager/Control";
    public static final String ZONE_GROUP_TOPOLOGY_PATH = "/ZoneGroupTopology/Control";
    public static final String AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1";
    public static final String RENDERING_CONTROL = "urn:schemas-upnp-org:service:RenderingControl:1";
    public static final String CONNECTION_MANAGER = "urn:schemas-upnp-org:service:ConnectionManager:1";
    public static final String ZONE_GROUP_TOPOLOGY = "urn:schemas-upnp-org:service:ZoneGroupTopology:1";

    private SonosEndpoints() {
    }

    /**
     * Whether a player location (from a topology answer) may be called: F1's shape rules (http, IP
     * literal, explicit port; {@link DeviceFetch}) and an address on the local network — the
     * appliance never follows a device-supplied topology to the internet.
     */
    public static boolean isLanLocation(URI location) {
        if (location == null || !DeviceFetch.isSafeToFetch(location, location.getHost())) {
            return false;
        }
        try {
            InetAddress address = InetAddress.getByName(location.getHost()); // an IP literal: no lookup
            return address.isSiteLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }

    public static ServiceEndpoint endpoint(String host, int port, String path, String serviceType) {
        return new ServiceEndpoint(serviceType, URI.create("http://" + host + ":" + port + path), null);
    }
}

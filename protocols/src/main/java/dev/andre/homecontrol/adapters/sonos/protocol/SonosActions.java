package dev.andre.homecontrol.adapters.sonos.protocol;

import dev.andre.homecontrol.adapters.upnp.protocol.SoapRequest;
import dev.andre.homecontrol.adapters.upnp.protocol.UpnpActions;

public final class SonosActions {

    private SonosActions() {
    }

    public static SoapRequest getZoneGroupState() {
        return UpnpActions.request(SonosEndpoints.ZONE_GROUP_TOPOLOGY, "GetZoneGroupState");
    }
}

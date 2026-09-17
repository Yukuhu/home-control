package dev.andre.homecontrol.adapters.sonos;

import dev.andre.homecontrol.adapters.upnp.FakeUpnpRenderer;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceKind;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

/** One Sonos player: fixed Sonos paths, ZoneGroupTopology, x-rincon joining, coordinator-only transport. */
public class FakeSonosPlayer extends FakeUpnpRenderer {

    static final Layout SONOS = new Layout("/xml/device_description.xml", "/MediaRenderer/AVTransport/Control",
            "/MediaRenderer/RenderingControl/Control", "/MediaRenderer/ConnectionManager/Control",
            "fixtures/ssdp/sonos-description.xml");
    static final String ZONE_GROUP_TOPOLOGY = "urn:schemas-upnp-org:service:ZoneGroupTopology:1";
    private static final Set<String> COORDINATOR_ONLY = Set.of("SetAVTransportURI", "Play", "Pause", "Stop");

    private final FakeSonosHousehold household;
    private final String uuid;
    private final String zoneName;

    FakeSonosPlayer(FakeSonosHousehold household, String bindAddress, String uuid, String zoneName) throws IOException {
        super(bindAddress, SONOS);
        this.household = household;
        this.uuid = uuid;
        this.zoneName = zoneName;
        setSink("http-get:*:audio/mpeg:*,http-get:*:audio/flac:*,http-get:*:audio/mp4:*,x-rincon-mp3radio:*:*:*,x-rincon:*:*:*");
    }

    public String uuid() {
        return uuid;
    }

    public String zoneName() {
        return zoneName;
    }

    @Override
    public String searchResponse() {
        return "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age = 1800\r\nEXT:\r\nLOCATION: " + location()
                + "\r\nSERVER: Linux UPnP/1.0 Sonos/85.0-65020 (ZPS1)\r\nST: urn:schemas-upnp-org:device:ZonePlayer:1\r\nUSN: uuid:"
                + uuid + "::urn:schemas-upnp-org:device:ZonePlayer:1\r\nX-RINCON-HOUSEHOLD: " + FakeSonosHousehold.HOUSEHOLD
                + "\r\nX-RINCON-BOOTSEQ: 98\r\n\r\n";
    }

    @Override
    public Device device(String id) {
        return new Device(id, zoneName, DeviceKind.SONOS, host(),
                Map.of("sonos", Map.of("uuid", uuid, "port", String.valueOf(port()))), Instant.now());
    }

    @Override
    protected String serviceType(String path) {
        return path.equals("/ZoneGroupTopology/Control") ? ZONE_GROUP_TOPOLOGY : super.serviceType(path);
    }

    @Override
    protected Map<String, String> perform(String serviceType, String action, Map<String, String> arguments) {
        if (ZONE_GROUP_TOPOLOGY.equals(serviceType)) {
            if (!action.equals("GetZoneGroupState")) {
                throw new Fault(401, "Invalid Action");
            }
            return ordered("ZoneGroupState", household.zoneGroupState());
        }
        if (AV_TRANSPORT.equals(serviceType)) {
            String uri = arguments.getOrDefault("CurrentURI", "");
            if (action.equals("SetAVTransportURI") && uri.startsWith("x-rincon:")) {
                household.join(uuid, uri.substring("x-rincon:".length()));
                return Map.of();
            }
            if (action.equals("BecomeCoordinatorOfStandaloneGroup")) {
                household.leave(uuid);
                return Map.of();
            }
            boolean coordinator = household.isCoordinator(uuid);
            if (!coordinator && COORDINATOR_ONLY.contains(action)) {
                throw new Fault(701, "Transition not available"); // what a member answers; sessions must never hit it
            }
            if (!coordinator && action.equals("GetPositionInfo")) {
                return ordered("Track", "1", "TrackDuration", "0:00:00", "TrackMetaData", "",
                        "TrackURI", "x-rincon:" + household.coordinatorOf(uuid), "RelTime", "0:00:00",
                        "AbsTime", "NOT_IMPLEMENTED", "RelCount", "2147483647", "AbsCount", "2147483647");
            }
        }
        return super.perform(serviceType, action, arguments);
    }
}

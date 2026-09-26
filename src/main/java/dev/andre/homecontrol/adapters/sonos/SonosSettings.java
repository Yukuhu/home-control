package dev.andre.homecontrol.adapters.sonos;

import dev.andre.homecontrol.adapters.sonos.protocol.SonosEndpoints;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DiscoveredDevice;

import java.util.LinkedHashMap;
import java.util.Map;

/** Per-device settings under {@code adapters.sonos}: the player's RINCON id and HTTP port. */
public record SonosSettings(String uuid, int port) {

    public static final String ADAPTER_ID = "sonos";
    static final String UUID_KEY = "uuid";
    static final String PORT_KEY = "port";

    public static SonosSettings of(Device device) {
        if (!device.hasAdapter(ADAPTER_ID)) {
            throw new IllegalArgumentException("Device " + device.id() + " has no sonos adapter");
        }
        Map<String, String> settings = device.adapterSettings(ADAPTER_ID);
        int port;
        try {
            port = Integer.parseInt(settings.getOrDefault(PORT_KEY, String.valueOf(SonosEndpoints.DEFAULT_PORT)));
        } catch (NumberFormatException _) {
            port = SonosEndpoints.DEFAULT_PORT;
        }
        return new SonosSettings(settings.get(UUID_KEY), port);
    }

    public static SonosSettings from(DiscoveredDevice found) {
        return new SonosSettings(found.attributes().get(UUID_KEY), found.port());
    }

    public Map<String, String> toMap() {
        Map<String, String> map = new LinkedHashMap<>();
        if (uuid != null) {
            map.put(UUID_KEY, uuid);
        }
        map.put(PORT_KEY, String.valueOf(port));
        return map;
    }
}

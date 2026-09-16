package dev.andre.homecontrol.adapters.cast;

import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DiscoveredDevice;

import java.util.LinkedHashMap;
import java.util.Map;

/** The Cast adapter's per-device settings, as stored under {@code adapters.cast}. */
public record CastSettings(int port, String castId, String model) {

    public static final String ADAPTER_ID = "cast";
    public static final int DEFAULT_PORT = 8009;
    static final String PORT = "port";
    static final String CAST_ID = "castId";
    static final String MODEL = "model";

    public static CastSettings of(Device device) {
        if (!device.hasAdapter(ADAPTER_ID)) {
            throw new IllegalArgumentException("Device " + device.id() + " has no cast adapter");
        }
        Map<String, String> settings = device.adapterSettings(ADAPTER_ID);
        int port = Integer.parseInt(settings.getOrDefault(PORT, String.valueOf(DEFAULT_PORT)));
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException(
                    "Device " + device.id() + " has a cast port outside 1-65535: " + port);
        }
        return new CastSettings(port, settings.get(CAST_ID), settings.get(MODEL));
    }

    /** mDNS TXT {@code id} is the receiver's stable UUID; {@code md} its model name. */
    public static CastSettings from(DiscoveredDevice found) {
        return new CastSettings(found.port(), found.attributes().get("id"), found.attributes().get("md"));
    }

    public Map<String, String> toMap() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put(PORT, String.valueOf(port));
        if (castId != null) {
            map.put(CAST_ID, castId);
        }
        if (model != null) {
            map.put(MODEL, model);
        }
        return map;
    }
}

package dev.andre.homecontrol.adapters.upnp;

import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DiscoveredDevice;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/** Per-device settings under {@code adapters.upnp}. The location is a hint; the UDN is the identity. */
public record UpnpSettings(String udn, URI location, String model) {

    public static final String ADAPTER_ID = "upnp";
    static final String UDN = "udn";
    static final String LOCATION = "location";
    static final String MODEL = "model";

    public static UpnpSettings of(Device device) {
        if (!device.hasAdapter(ADAPTER_ID)) {
            throw new IllegalArgumentException("Device " + device.id() + " has no upnp adapter");
        }
        Map<String, String> settings = device.adapterSettings(ADAPTER_ID);
        return new UpnpSettings(settings.get(UDN), uri(settings.get(LOCATION)), settings.get(MODEL));
    }

    public static UpnpSettings from(DiscoveredDevice found) {
        return new UpnpSettings(found.attributes().get(UDN), uri(found.attributes().get(LOCATION)), found.attributes().get(MODEL));
    }

    public Map<String, String> toMap() {
        Map<String, String> map = new LinkedHashMap<>();
        if (udn != null) {
            map.put(UDN, udn);
        }
        if (location != null) {
            map.put(LOCATION, location.toString());
        }
        if (model != null) {
            map.put(MODEL, model);
        }
        return map;
    }

    private static URI uri(String value) {
        try {
            return value == null || value.isBlank() ? null : URI.create(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}

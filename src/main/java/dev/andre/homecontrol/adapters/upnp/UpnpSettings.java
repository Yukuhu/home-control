package dev.andre.homecontrol.adapters.upnp;

import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DiscoveredDevice;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/** Per-device settings under {@code adapters.upnp}. The location is a hint; the UDN is the identity. */
public record UpnpSettings(String udn, URI location, String model) {

    public static final String ADAPTER_ID = "upnp";
    static final String UDN_KEY = "udn";
    static final String LOCATION_KEY = "location";
    static final String MODEL_KEY = "model";

    public static UpnpSettings of(Device device) {
        if (!device.hasAdapter(ADAPTER_ID)) {
            throw new IllegalArgumentException("Device " + device.id() + " has no upnp adapter");
        }
        Map<String, String> settings = device.adapterSettings(ADAPTER_ID);
        return new UpnpSettings(settings.get(UDN_KEY), uri(settings.get(LOCATION_KEY)), settings.get(MODEL_KEY));
    }

    public static UpnpSettings from(DiscoveredDevice found) {
        return new UpnpSettings(found.attributes().get(UDN_KEY), uri(found.attributes().get(LOCATION_KEY)), found.attributes().get(MODEL_KEY));
    }

    public Map<String, String> toMap() {
        Map<String, String> map = new LinkedHashMap<>();
        if (udn != null) {
            map.put(UDN_KEY, udn);
        }
        if (location != null) {
            map.put(LOCATION_KEY, location.toString());
        }
        if (model != null) {
            map.put(MODEL_KEY, model);
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

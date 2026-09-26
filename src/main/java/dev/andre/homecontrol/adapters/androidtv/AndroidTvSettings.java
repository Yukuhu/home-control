package dev.andre.homecontrol.adapters.androidtv;

import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceKind;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** The Android TV adapter's per-device settings, as stored under {@code adapters.androidtv}. */
public record AndroidTvSettings(int port, String certificateFingerprint) {

    public static final String ADAPTER_ID = "androidtv";
    public static final int DEFAULT_PORT = 6466;
    static final String PORT_KEY = "port";
    static final String FINGERPRINT = "certificateFingerprint";

    public static AndroidTvSettings of(Device device) {
        if (!device.hasAdapter(ADAPTER_ID)) {
            throw new IllegalArgumentException("Device " + device.id() + " has no androidtv adapter");
        }
        Map<String, String> settings = device.adapterSettings(ADAPTER_ID);
        int port = Integer.parseInt(settings.getOrDefault(PORT_KEY, String.valueOf(DEFAULT_PORT)));
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException(
                    "Device " + device.id() + " has an androidtv port outside 1-65535: " + port);
        }
        return new AndroidTvSettings(port, settings.get(FINGERPRINT));
    }

    public Map<String, String> toMap() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put(PORT_KEY, String.valueOf(port));
        if (certificateFingerprint != null) {
            map.put(FINGERPRINT, certificateFingerprint);
        }
        return map;
    }

    /** The certificate alias is the device id, as it always was; the keystore needs no migration. */
    public static String certificateAlias(Device device) {
        return device.id();
    }

    /** Same argument order as the v1 {@code Device} constructor, so call sites are a rename. */
    public static Device device(String id, String name, String host, int port,
                                String certificateFingerprint, Instant lastSeen) {
        return new Device(id, name, DeviceKind.ANDROID_TV, host,
                Map.of(ADAPTER_ID, new AndroidTvSettings(port, certificateFingerprint).toMap()),
                lastSeen);
    }
}

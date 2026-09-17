package dev.andre.homecontrol.adapters.bluetooth.bluez;

import java.util.List;

public record BluetoothDeviceInfo(String address, String name, String icon, boolean paired, boolean trusted,
                                  boolean connected, List<String> uuids, Short rssi) {

    /** Advanced Audio Distribution Profile, sink role: what a speaker or headphones offer. */
    public static final String A2DP_SINK = "0000110b-0000-1000-8000-00805f9b34fb";

    public BluetoothDeviceInfo {
        uuids = uuids == null ? List.of() : List.copyOf(uuids);
    }

    public boolean audioSink() {
        return uuids.stream().anyMatch(A2DP_SINK::equalsIgnoreCase);
    }

    /** Many speakers reveal their services only after pairing. */
    public boolean servicesKnown() {
        return !uuids.isEmpty();
    }

    public boolean mayBeSpeaker() {
        return audioSink() || !servicesKnown() || (icon != null && icon.startsWith("audio-"));
    }

    public String displayName() {
        return name == null || name.isBlank() ? address : name.strip();
    }
}

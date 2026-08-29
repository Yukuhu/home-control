package dev.andre.shield.device;

public enum DeviceStatus {
    DISCONNECTED, CONNECTING, CONNECTED,
    /** The device rejected our certificate; reconnecting cannot fix this, re-pairing can. */
    UNPAIRED
}

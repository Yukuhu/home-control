package dev.andre.homecontrol.core;

/** The adapter settings of a device woken with a Wake-on-LAN magic packet ({@link Capability#WAKE_ON_LAN}). */
public final class WakeOnLanSettings {

    /** The MAC the magic packet goes to. */
    public static final String MAC_ADDRESS = "macAddress";
    /** {@code "true"} once the user typed the MAC; adapters then stop replacing it with what the device reports. */
    public static final String MAC_ADDRESS_MANUAL = "macAddressManual";

    private WakeOnLanSettings() {
    }
}

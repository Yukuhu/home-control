package dev.andre.homecontrol.core;

/** An adapter that switches its devices on with a Wake-on-LAN magic packet. The MAC lives in its settings. */
public interface WakeOnLanAdapter extends DeviceAdapter {

    String MAC_ADDRESS = "macAddress";

    /** {@code "true"} once the user typed the MAC; adapters then stop replacing it with what the device reports. */
    String MAC_ADDRESS_MANUAL = "macAddressManual";
}

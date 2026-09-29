package dev.andre.homecontrol.core;

import java.util.Optional;

/** The device settings a person can change on the setup page: how it is woken with Wake-on-LAN. */
public interface DeviceSettings {

    /** True when one of the device's adapters can switch it on with Wake-on-LAN. */
    boolean wakesOnLan(String id);

    /** The MAC the first of the device's Wake-on-LAN adapters knows, learned or entered; empty when none does. */
    Optional<String> wakeOnLanMac(String id);

    /**
     * Stores a hand-entered MAC on every Wake-on-LAN adapter of the device and stops adapters from replacing it; blank
     * clears it so they learn it again. No reconnect: handles read the MAC from the registry when they wake the device.
     * An invalid MAC throws {@link IllegalArgumentException} before anything is written.
     */
    void setWakeOnLanMac(String id, String mac);
}

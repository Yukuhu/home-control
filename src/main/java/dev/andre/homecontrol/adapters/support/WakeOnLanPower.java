package dev.andre.homecontrol.adapters.support;

import dev.andre.homecontrol.adapters.net.WakeOnLan;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceOfflineException;
import dev.andre.homecontrol.core.WakeOnLanSettings;

import java.io.IOException;
import java.util.function.Supplier;

/**
 * Switches a TV on with a Wake-on-LAN packet to the MAC address in its adapter settings, read when it wakes: the user
 * may have typed it in meanwhile. Switching off stays with each protocol.
 */
public final class WakeOnLanPower {

    private final String deviceName;
    private final Supplier<Device> device;
    private final String adapterId;
    private final WakeOnLan wakeOnLan;

    public WakeOnLanPower(String deviceName, Supplier<Device> device, String adapterId, WakeOnLan wakeOnLan) {
        this.deviceName = deviceName;
        this.device = device;
        this.adapterId = adapterId;
        this.wakeOnLan = wakeOnLan;
    }

    /** Sends the packet; with no MAC address known, or a packet that cannot be sent, the device counts as offline. */
    public void wake() {
        String mac = device.get().adapterSettings(adapterId).get(WakeOnLanSettings.MAC_ADDRESS);
        if (mac == null || mac.isBlank()) {
            throw new DeviceOfflineException(deviceName + " is off and no MAC address is known for Wake-on-LAN;"
                    + " switch it on once by hand or enter its MAC address on the setup page");
        }
        try {
            wakeOnLan.wake(mac);
        } catch (IOException | IllegalArgumentException e) {
            throw new DeviceOfflineException("Could not send the Wake-on-LAN packet: " + e.getMessage());
        }
    }
}

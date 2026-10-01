package dev.andre.homecontrol.adapters.support;

import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.LearnedSettings;
import dev.andre.homecontrol.core.WakeOnLanSettings;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Keeps the MAC address a TV reports, for Wake-on-LAN: stored through {@link LearnedSettings} unless the user typed
 * one in or it is already known. The settings are read from the device itself, never from its pairing secrets.
 */
public final class LearnedMac {

    private final Supplier<Device> device;
    private final String adapterId;
    private final LearnedSettings learned;

    public LearnedMac(Supplier<Device> device, String adapterId, LearnedSettings learned) {
        this.device = device;
        this.adapterId = adapterId;
        this.learned = learned;
    }

    public void offer(String mac) {
        Map<String, String> settings = device.get().adapterSettings(adapterId);
        if ("true".equals(settings.get(WakeOnLanSettings.MAC_ADDRESS_MANUAL))
                || mac.equals(settings.get(WakeOnLanSettings.MAC_ADDRESS))) {
            return;
        }
        learned.store(Map.of(WakeOnLanSettings.MAC_ADDRESS, mac));
    }
}

package dev.andre.homecontrol.adapters.tizen;

import dev.andre.homecontrol.core.MacAddress;

import java.util.Optional;

/** The parts of {@code GET /api/v2/} the adapter uses. */
record TizenDeviceInfo(String name, String modelName, String powerState, String wifiMac, boolean tokenAuthSupport) {

    /** Sets older than 2018 do not report PowerState; answering at all then means on. */
    boolean on() {
        return powerState.isEmpty() || powerState.equalsIgnoreCase("on");
    }

    /** Named {@code wifiMac}, but it is the MAC of the active interface, wired or not. */
    Optional<String> macAddress() {
        try {
            return wifiMac.isEmpty() ? Optional.empty() : Optional.of(MacAddress.normalize(wifiMac));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}

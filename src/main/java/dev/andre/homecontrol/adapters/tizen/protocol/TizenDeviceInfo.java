package dev.andre.homecontrol.adapters.tizen.protocol;

/** The parts of {@code GET /api/v2/} the adapter uses. */
public record TizenDeviceInfo(String name, String modelName, String powerState, String wifiMac, boolean tokenAuthSupport) {

    /** Sets older than 2018 do not report PowerState; answering at all then means on. */
    public boolean on() {
        return powerState.isEmpty() || powerState.equalsIgnoreCase("on");
    }
}

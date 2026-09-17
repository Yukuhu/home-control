package dev.andre.homecontrol.adapters.webos;

import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.WakeOnLanAdapter;

import java.util.Map;

/**
 * The webOS adapter's settings under {@code adapters.webos} in {@code devices.json}. The client key
 * is a credential: it never goes into a log, an error message or a page.
 */
public record WebOsSettings(String clientKey, String macAddress, boolean macAddressManual) {

    public static final String ADAPTER_ID = "webos";
    static final String CLIENT_KEY = "clientKey";

    public static WebOsSettings of(Device device) {
        Map<String, String> settings = device.adapterSettings(ADAPTER_ID);
        return new WebOsSettings(blankToNull(settings.get(CLIENT_KEY)),
                blankToNull(settings.get(WakeOnLanAdapter.MAC_ADDRESS)),
                "true".equals(settings.get(WakeOnLanAdapter.MAC_ADDRESS_MANUAL)));
    }

    @Override
    public String toString() {
        return "WebOsSettings[clientKey=" + (clientKey == null ? "none" : "stored")
                + ", macAddress=" + macAddress + ", macAddressManual=" + macAddressManual + "]";
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}

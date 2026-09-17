package dev.andre.homecontrol.adapters.tizen;

import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.WakeOnLanAdapter;

import java.util.Map;

/**
 * Under {@code adapters.tizen}: {@code paired=true} once the user allowed us (older firmware issues
 * no token, so the flag, not the token, says whether connecting is allowed), the token if any,
 * and the Wake-on-LAN MAC. The token is a credential: never logged, never shown.
 */
public record TizenSettings(String token, boolean paired, String macAddress, boolean macAddressManual) {

    public static final String ADAPTER_ID = "tizen";
    static final String TOKEN = "token";
    static final String PAIRED = "paired";

    public static TizenSettings of(Device device) {
        Map<String, String> settings = device.adapterSettings(ADAPTER_ID);
        return new TizenSettings(blankToNull(settings.get(TOKEN)), "true".equals(settings.get(PAIRED)),
                blankToNull(settings.get(WakeOnLanAdapter.MAC_ADDRESS)),
                "true".equals(settings.get(WakeOnLanAdapter.MAC_ADDRESS_MANUAL)));
    }

    @Override
    public String toString() {
        return "TizenSettings[token=" + (token == null ? "none" : "stored") + ", paired=" + paired
                + ", macAddress=" + macAddress + ", macAddressManual=" + macAddressManual + "]";
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}

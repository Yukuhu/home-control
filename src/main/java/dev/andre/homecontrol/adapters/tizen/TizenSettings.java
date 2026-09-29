package dev.andre.homecontrol.adapters.tizen;

import dev.andre.homecontrol.adapters.support.PairingKeys;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceSecrets;
import dev.andre.homecontrol.core.WakeOnLanSettings;

import java.util.Map;

/**
 * Under {@code adapters.tizen}: {@code paired=true} once the user allowed us (older firmware issues
 * no token, so the flag, not the token, says whether connecting is allowed), the reference naming the
 * token's device secret if the TV issued one, and the Wake-on-LAN MAC. Before 2B the token sat here as
 * {@code token}; {@link TizenAdapter#migrate} moves it. The token is a credential: never logged, never shown.
 */
public record TizenSettings(String keyRef, String token, boolean paired, String macAddress, boolean macAddressManual) {

    public static final String ADAPTER_ID = "tizen";
    static final String KEY_REF = PairingKeys.KEY_REF;
    static final String LEGACY_TOKEN = "token";
    static final String PAIRED_KEY = "paired";

    public static TizenSettings of(Device device, DeviceSecrets secrets) {
        Map<String, String> settings = device.adapterSettings(ADAPTER_ID);
        PairingKeys keys = keys(secrets);
        return new TizenSettings(keys.referenceOf(device), keys.keyOf(device), "true".equals(settings.get(PAIRED_KEY)),
                blankToNull(settings.get(WakeOnLanSettings.MAC_ADDRESS)),
                "true".equals(settings.get(WakeOnLanSettings.MAC_ADDRESS_MANUAL)));
    }

    static PairingKeys keys(DeviceSecrets secrets) {
        return new PairingKeys(ADAPTER_ID, TizenSettings::secretName, secrets);
    }

    static String secretName(String keyRef) {
        return DeviceSecrets.PREFIX + ADAPTER_ID + "." + keyRef + ".token";
    }

    @Override
    public String toString() {
        return "TizenSettings[keyRef=" + keyRef + ", token=" + (token == null ? "none" : "stored") + ", paired=" + paired
                + ", macAddress=" + macAddress + ", macAddressManual=" + macAddressManual + "]";
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}

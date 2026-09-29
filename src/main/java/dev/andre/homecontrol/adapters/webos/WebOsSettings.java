package dev.andre.homecontrol.adapters.webos;

import dev.andre.homecontrol.adapters.support.PairingKeys;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceSecrets;
import dev.andre.homecontrol.core.WakeOnLanAdapter;

import java.util.Map;

/**
 * The webOS adapter's settings under {@code adapters.webos} in devices.json. The client key is a device secret
 * named by {@code keyRef}, never in devices.json; before 2B it was stored there as {@code clientKey}, and
 * {@link WebOsAdapter#migrate} moves it. The key never goes into a log, an error message or a page.
 */
public record WebOsSettings(String keyRef, String clientKey, String macAddress, boolean macAddressManual) {

    public static final String ADAPTER_ID = "webos";
    static final String KEY_REF = PairingKeys.KEY_REF;
    static final String LEGACY_CLIENT_KEY = "clientKey";

    public static WebOsSettings of(Device device, DeviceSecrets secrets) {
        Map<String, String> settings = device.adapterSettings(ADAPTER_ID);
        PairingKeys keys = keys(secrets);
        return new WebOsSettings(keys.referenceOf(device), keys.keyOf(device),
                blankToNull(settings.get(WakeOnLanAdapter.MAC_ADDRESS)),
                "true".equals(settings.get(WakeOnLanAdapter.MAC_ADDRESS_MANUAL)));
    }

    static PairingKeys keys(DeviceSecrets secrets) {
        return new PairingKeys(ADAPTER_ID, WebOsSettings::secretName, secrets);
    }

    static String secretName(String keyRef) {
        return DeviceSecrets.PREFIX + ADAPTER_ID + "." + keyRef + ".client-key";
    }

    @Override
    public String toString() {
        return "WebOsSettings[keyRef=" + keyRef + ", clientKey=" + (clientKey == null ? "none" : "stored")
                + ", macAddress=" + macAddress + ", macAddressManual=" + macAddressManual + "]";
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}

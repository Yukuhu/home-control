package dev.andre.homecontrol.adapters.bluetooth;

import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.MacAddress;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Per-device settings under {@code adapters.bluetooth}: the speaker's MAC, its adapter's MAC, an optional audio device. */
public record BluetoothSettings(String address, String adapter, String audioDevice) {

    public static final String ADAPTER_ID = "bluetooth";
    private static final Pattern AUDIO_DEVICE = Pattern.compile("[A-Za-z0-9_.:/=,@+-]{1,200}");

    public BluetoothSettings {
        address = MacAddress.normalize(address);
        adapter = adapter == null || adapter.isBlank() ? "" : MacAddress.normalize(adapter);
        audioDevice = audioDevice == null ? "" : audioDevice.strip();
        if (!audioDevice.isEmpty() && !AUDIO_DEVICE.matcher(audioDevice).matches()) {
            throw new IllegalArgumentException("An audio device id may only contain letters, digits and _ . : / = , @ + -");
        }
    }

    public static BluetoothSettings of(Device device) {
        Map<String, String> settings = device.adapterSettings(ADAPTER_ID);
        return new BluetoothSettings(settings.get("address"), settings.get("adapter"), settings.get("audioDevice"));
    }

    public static String deviceId(String address) {
        return "bluetooth-" + MacAddress.normalize(address).toLowerCase(Locale.ROOT).replace(':', '-');
    }

    public BluetoothSettings withAudioDevice(String value) {
        return new BluetoothSettings(address, adapter, value);
    }

    public Map<String, String> toMap() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("address", address);
        map.put("adapter", adapter);
        if (!audioDevice.isEmpty()) {
            map.put("audioDevice", audioDevice);
        }
        return map;
    }
}

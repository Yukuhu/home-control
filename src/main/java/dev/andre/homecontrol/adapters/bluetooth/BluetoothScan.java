package dev.andre.homecontrol.adapters.bluetooth;

import dev.andre.homecontrol.adapters.bluetooth.bluez.BluetoothDeviceInfo;

import java.time.Instant;
import java.util.List;

public record BluetoothScan(Instant scannedAt, List<BluetoothDeviceInfo> speakers, int hiddenCount, String error) {

    public static final BluetoothScan NONE = new BluetoothScan(null, List.of(), 0, null);

    public BluetoothScan {
        speakers = List.copyOf(speakers);
    }

    public boolean ran() {
        return scannedAt != null;
    }
}

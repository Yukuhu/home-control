package dev.andre.homecontrol.adapters.bluetooth.bluez;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public record BluetoothAdapterInfo(String id, String address, String alias, boolean powered) {

    /** Blank {@code wanted}: the first powered adapter, else the first. Otherwise the adapter with that id or MAC. */
    public static Optional<BluetoothAdapterInfo> select(List<BluetoothAdapterInfo> adapters, String wanted) {
        if (wanted == null || wanted.isBlank()) {
            return adapters.stream().filter(BluetoothAdapterInfo::powered).findFirst()
                    .or(() -> adapters.stream().findFirst());
        }
        return adapters.stream()
                .filter(adapter -> adapter.id().equalsIgnoreCase(wanted.strip()) || adapter.address().equalsIgnoreCase(wanted.strip()))
                .findFirst();
    }

    public static String describe(List<BluetoothAdapterInfo> adapters) {
        return adapters.isEmpty() ? "no adapter"
                : adapters.stream().map(a -> a.id() + " (" + a.address() + ")").collect(Collectors.joining(", "));
    }
}

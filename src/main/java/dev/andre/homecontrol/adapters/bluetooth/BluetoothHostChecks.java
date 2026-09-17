package dev.andre.homecontrol.adapters.bluetooth;

import dev.andre.homecontrol.adapters.bluetooth.bluez.BluetoothAdapterInfo;
import dev.andre.homecontrol.adapters.bluetooth.bluez.BluezClient;
import dev.andre.homecontrol.adapters.bluetooth.bluez.BluezException;
import dev.andre.homecontrol.adapters.bluetooth.bluez.BluezFailure;
import dev.andre.homecontrol.adapters.bluetooth.bluez.BluezFailures;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The three checks the setup page shows under "Bluetooth speakers": is the D-Bus socket there, does
 * BlueZ answer, is there a usable adapter. Cached for {@code hostCheckCacheSeconds} so the setup
 * page does not hit D-Bus on every render.
 */
public class BluetoothHostChecks {

    private final BluetoothProperties properties;
    private final BluezClient bluez;
    private final Clock clock;

    private List<HostCheck> cached;
    private Instant cachedAt = Instant.MIN;

    public BluetoothHostChecks(BluetoothProperties properties, BluezClient bluez, Clock clock) {
        this.properties = properties;
        this.bluez = bluez;
        this.clock = clock;
    }

    public synchronized List<HostCheck> results() {
        Instant now = clock.instant();
        if (cached != null && now.isBefore(cachedAt.plusSeconds(properties.hostCheckCacheSeconds()))) {
            return cached;
        }
        cached = run();
        cachedAt = now;
        return cached;
    }

    public synchronized void invalidate() {
        cached = null;
    }

    private List<HostCheck> run() {
        HostCheck socket = socketCheck();
        List<BluetoothAdapterInfo> adapters = null;
        HostCheck bluezCheck;
        if (!socket.ok()) {
            bluezCheck = new HostCheck("bluez", "BlueZ", false, "Needs the D-Bus socket first");
        } else {
            try {
                // Fetched once and reused by the adapter check below: BlueZ answering IS the list.
                adapters = bluez.adapters();
                bluezCheck = new HostCheck("bluez", "BlueZ", true, "BlueZ answered on the system bus");
            } catch (BluezException e) {
                bluezCheck = new HostCheck("bluez", "BlueZ", false, e.getMessage());
            }
        }
        HostCheck adapter = adapterCheck(bluezCheck.ok(), adapters);
        return List.of(socket, bluezCheck, adapter);
    }

    private HostCheck socketCheck() {
        Optional<Path> socket = properties.dbusSocketPath();
        if (socket.isEmpty()) {
            return new HostCheck("dbus-socket", "D-Bus system socket", true, "Using " + properties.dbusAddress());
        }
        if (Files.exists(socket.get())) {
            return new HostCheck("dbus-socket", "D-Bus system socket", true, "Found " + socket.get());
        }
        return new HostCheck("dbus-socket", "D-Bus system socket", false, BluezFailures.noSocket(socket.get()));
    }

    private HostCheck adapterCheck(boolean bluezOk, List<BluetoothAdapterInfo> adapters) {
        if (!bluezOk) {
            return new HostCheck("adapter", "Bluetooth adapter", false, "Needs BlueZ first");
        }
        if (adapters.isEmpty()) {
            return new HostCheck("adapter", "Bluetooth adapter", false,
                    BluezFailures.message(BluezFailure.NO_ADAPTER, null));
        }
        Optional<BluetoothAdapterInfo> selected = BluetoothAdapterInfo.select(adapters, properties.adapter());
        if (selected.isEmpty()) {
            return new HostCheck("adapter", "Bluetooth adapter", false,
                    "Adapter " + properties.adapter() + " not found; the host has " + BluetoothAdapterInfo.describe(adapters));
        }
        BluetoothAdapterInfo adapter = selected.get();
        if (!adapter.powered()) {
            return new HostCheck("adapter", "Bluetooth adapter", false, adapter.id() + " (" + adapter.address()
                    + ") is powered off. Scanning switches it on; if that fails run rfkill unblock bluetooth on the host.");
        }
        return new HostCheck("adapter", "Bluetooth adapter", true, adapter.id() + " (" + adapter.address() + ")");
    }
}

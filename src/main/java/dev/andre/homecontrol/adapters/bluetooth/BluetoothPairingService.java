package dev.andre.homecontrol.adapters.bluetooth;

import dev.andre.homecontrol.adapters.bluetooth.bluez.BluetoothAdapterInfo;
import dev.andre.homecontrol.adapters.bluetooth.bluez.BluetoothDeviceInfo;
import dev.andre.homecontrol.adapters.bluetooth.bluez.BluezClient;
import dev.andre.homecontrol.adapters.bluetooth.bluez.BluezException;
import dev.andre.homecontrol.adapters.bluetooth.bluez.BluezFailure;
import dev.andre.homecontrol.adapters.bluetooth.bluez.BluezFailures;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.core.DeviceNotFoundException;
import dev.andre.homecontrol.core.MacAddress;
import dev.andre.homecontrol.device.DeviceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Scans for and pairs Bluetooth speakers through the host's BlueZ, and registers them as devices.
 * No Spring annotation: the bean is declared by {@link BluetoothConfiguration}.
 */
public class BluetoothPairingService {

    private static final Logger log = LoggerFactory.getLogger(BluetoothPairingService.class);

    private static final Comparator<BluetoothDeviceInfo> SPEAKERS_FIRST =
            Comparator.comparing((BluetoothDeviceInfo found) -> !found.audioSink())
                    .thenComparing((BluetoothDeviceInfo found) -> found.rssi() == null ? Integer.MIN_VALUE : (int) found.rssi(),
                            Comparator.reverseOrder())
                    .thenComparing(BluetoothDeviceInfo::displayName, String.CASE_INSENSITIVE_ORDER);

    private final BluezClient bluez;
    private final DeviceManager devices;
    private final BluetoothProperties properties;
    private final Clock clock;

    private volatile BluetoothScan lastScan = BluetoothScan.NONE;

    public BluetoothPairingService(BluezClient bluez, DeviceManager devices, BluetoothProperties properties) {
        this(bluez, devices, properties, Clock.systemUTC());
    }

    public BluetoothPairingService(BluezClient bluez, DeviceManager devices, BluetoothProperties properties, Clock clock) {
        this.bluez = bluez;
        this.devices = devices;
        this.properties = properties;
        this.clock = clock;
    }

    public BluetoothScan lastScan() {
        return lastScan;
    }

    public BluetoothScan scan() {
        BluetoothScan result;
        try {
            BluetoothAdapterInfo adapter = adapter();
            if (!adapter.powered()) {
                bluez.powerOn(adapter.address());
            }
            List<BluetoothDeviceInfo> found = bluez.discover(adapter.address(), Duration.ofSeconds(properties.scanSeconds()));
            List<BluetoothDeviceInfo> speakers = found.stream().filter(BluetoothDeviceInfo::mayBeSpeaker).sorted(SPEAKERS_FIRST).toList();
            result = new BluetoothScan(clock.instant(), speakers, found.size() - speakers.size(), null);
        } catch (BluezException e) {
            result = new BluetoothScan(clock.instant(), List.of(), 0, e.getMessage());
        }
        lastScan = result;
        return result;
    }

    public BluetoothPairing pair(String rawAddress) throws BluetoothSetupException {
        String address = mac(rawAddress);
        try {
            BluetoothAdapterInfo adapter = adapter();
            BluetoothDeviceInfo info = bluez.device(adapter.address(), address).orElseThrow(() -> new BluetoothSetupException(
                    address + " is not known to the host's Bluetooth adapter. Put the speaker into pairing mode and scan again."));
            if (info.servicesKnown() && !info.audioSink()) {
                throw notASpeaker(info);
            }
            if (!info.paired()) {
                ignoringAlreadyDone(() -> bluez.pair(adapter.address(), address));
            }
            bluez.trust(adapter.address(), address);
            String warning = connectIfNeeded(adapter, address, info);
            BluetoothDeviceInfo paired = bluez.device(adapter.address(), address).orElse(info);
            rejectNonSpeaker(adapter, address, paired);
            String id = BluetoothSettings.deviceId(address);
            Optional<Device> existing = devices.device(id);
            String name = existing.map(Device::name).orElse(paired.displayName());
            String audioDevice = existing.filter(d -> d.hasAdapter(BluetoothSettings.ADAPTER_ID))
                    .map(d -> BluetoothSettings.of(d).audioDevice()).orElse("");
            Device device = new Device(id, name, DeviceKind.BLUETOOTH, address,
                    Map.of(BluetoothSettings.ADAPTER_ID, new BluetoothSettings(address, adapter.address(), audioDevice).toMap()),
                    clock.instant());
            devices.adopt(device);
            return new BluetoothPairing(device, warning);
        } catch (BluezException e) {
            throw new BluetoothSetupException(e.getMessage());
        }
    }

    private String connectIfNeeded(BluetoothAdapterInfo adapter, String address, BluetoothDeviceInfo info) {
        if (info.connected()) {
            return null;
        }
        try {
            bluez.connect(adapter.address(), address);
            return null;
        } catch (BluezException e) {
            return "Paired " + info.displayName() + ", but it did not connect: " + e.getMessage();
        }
    }

    private void rejectNonSpeaker(BluetoothAdapterInfo adapter, String address, BluetoothDeviceInfo paired)
            throws BluetoothSetupException {
        if (paired.servicesKnown() && !paired.audioSink()) {
            try {
                bluez.remove(adapter.address(), address);
            } catch (BluezException e) {
                log.warn("Could not remove {} again: {}", address, e.getMessage());
            }
            throw notASpeaker(paired);
        }
    }

    public Device connect(String deviceId) throws BluetoothSetupException {
        Device device = registeredSpeaker(deviceId);
        BluetoothSettings settings = BluetoothSettings.of(device);
        try {
            bluez.connect(settings.adapter(), settings.address());
        } catch (BluezException e) {
            throw new BluetoothSetupException(e.getMessage());
        }
        return device;
    }

    public Device disconnect(String deviceId) throws BluetoothSetupException {
        Device device = registeredSpeaker(deviceId);
        BluetoothSettings settings = BluetoothSettings.of(device);
        try {
            bluez.disconnect(settings.adapter(), settings.address());
        } catch (BluezException e) {
            throw new BluetoothSetupException(e.getMessage());
        }
        return device;
    }

    public Device setAudioDevice(String deviceId, String audioDevice) throws BluetoothSetupException {
        Device device = registeredSpeaker(deviceId);
        BluetoothSettings settings;
        try {
            settings = BluetoothSettings.of(device).withAudioDevice(audioDevice);
        } catch (IllegalArgumentException e) {
            throw new BluetoothSetupException(e.getMessage());
        }
        Device updated = device.withAdapter(BluetoothSettings.ADAPTER_ID, settings.toMap());
        devices.adopt(updated);
        return updated;
    }

    private Device registeredSpeaker(String deviceId) {
        return devices.device(deviceId).filter(d -> d.hasAdapter(BluetoothSettings.ADAPTER_ID))
                .orElseThrow(() -> new DeviceNotFoundException("No Bluetooth speaker " + deviceId));
    }

    private BluetoothAdapterInfo adapter() throws BluezException {
        List<BluetoothAdapterInfo> adapters = bluez.adapters();
        if (adapters.isEmpty()) {
            throw new BluezException(BluezFailure.NO_ADAPTER, BluezFailures.message(BluezFailure.NO_ADAPTER, null));
        }
        return BluetoothAdapterInfo.select(adapters, properties.adapter())
                .orElseThrow(() -> new BluezException(BluezFailure.NO_ADAPTER,
                        "Adapter " + properties.adapter() + " not found; the host has " + BluetoothAdapterInfo.describe(adapters)));
    }

    private static String mac(String rawAddress) throws BluetoothSetupException {
        try {
            return MacAddress.normalize(rawAddress);
        } catch (IllegalArgumentException e) {
            throw new BluetoothSetupException(e.getMessage());
        }
    }

    private static BluetoothSetupException notASpeaker(BluetoothDeviceInfo info) {
        return new BluetoothSetupException(info.displayName() + " is not a speaker or headphones (no A2DP audio sink)");
    }

    @FunctionalInterface
    private interface BluezAction {
        void run() throws BluezException;
    }

    private static void ignoringAlreadyDone(BluezAction action) throws BluezException {
        try {
            action.run();
        } catch (BluezException e) {
            if (e.failure() != BluezFailure.ALREADY_DONE) {
                throw e;
            }
        }
    }
}

package dev.andre.homecontrol.adapters.bluetooth;

import dev.andre.homecontrol.adapters.bluetooth.bluez.BluezClient;
import dev.andre.homecontrol.adapters.bluetooth.bluez.BluezException;
import dev.andre.homecontrol.adapters.bluetooth.player.AudioDeviceResolver;
import dev.andre.homecontrol.adapters.bluetooth.player.MpvLauncher;
import dev.andre.homecontrol.adapters.bluetooth.player.MpvPlayer;
import dev.andre.homecontrol.core.Capability;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceAdapter;
import dev.andre.homecontrol.core.DeviceHandle;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.DiscoveredDevice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/** Bluetooth speakers (spec epic J): server-side playback through the host's BlueZ and mpv. */
public class BluetoothSpeakerAdapter implements DeviceAdapter {

    private static final Logger log = LoggerFactory.getLogger(BluetoothSpeakerAdapter.class);

    private final BluetoothProperties properties;
    private final BluezClient bluez;
    private final MpvLauncher launcher;

    public BluetoothSpeakerAdapter(BluetoothProperties properties, BluezClient bluez, MpvLauncher launcher) {
        this.properties = properties;
        this.bluez = bluez;
        this.launcher = launcher;
    }

    @Override
    public String id() {
        return BluetoothSettings.ADAPTER_ID;
    }

    @Override
    public DeviceKind kind() {
        return DeviceKind.BLUETOOTH;
    }

    @Override
    public Set<Capability> capabilities(Device device) {
        return EnumSet.of(Capability.LOCAL_AUDIO_SINK, Capability.VOLUME);
    }

    @Override
    public DeviceHandle connect(Device device, Consumer<DeviceState> onChange) {
        MpvPlayer player = new MpvPlayer(launcher, MpvPlayer.socketFor(properties.runtimeDir(), device.id()),
                Duration.ofSeconds(properties.playerStartTimeoutSeconds()), Duration.ofSeconds(properties.loadTimeoutSeconds()),
                Duration.ofSeconds(properties.commandTimeoutSeconds()));
        AudioDeviceResolver audioDevices = new AudioDeviceResolver(launcher, properties.audioDeviceTemplate(),
                Duration.ofSeconds(properties.playerStartTimeoutSeconds()));
        BluetoothSpeakerSession session = new BluetoothSpeakerSession(device, properties, bluez, player, audioDevices, onChange);
        session.start();
        return session;
    }

    @Override
    public void forget(Device device) {
        try {
            BluetoothSettings settings = BluetoothSettings.of(device);
            bluez.remove(settings.adapter(), settings.address());
        } catch (BluezException | IllegalArgumentException e) {
            log.warn("Could not unpair {} on the host: {}", device.name(), e.getMessage());
        }
    }

    @Override
    public List<DiscoveredDevice> discovered() {
        // Scan results live in the Bluetooth setup section (BluetoothPairingService), not the
        // generic setup-page "discovered devices" list every other adapter feeds.
        return List.of();
    }
}

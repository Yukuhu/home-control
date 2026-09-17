package dev.andre.homecontrol.adapters.bluetooth;

import dev.andre.homecontrol.adapters.bluetooth.bluez.BluetoothDeviceInfo;
import dev.andre.homecontrol.adapters.bluetooth.bluez.BluezClient;
import dev.andre.homecontrol.adapters.bluetooth.bluez.BluezException;
import dev.andre.homecontrol.core.Action;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceHandle;
import dev.andre.homecontrol.core.DeviceOfflineException;
import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.DeviceStatus;
import dev.andre.homecontrol.core.UnsupportedActionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** One Bluetooth speaker: polls BlueZ on one virtual thread and publishes state changes. */
public class BluetoothSpeakerSession implements DeviceHandle {

    private static final Logger log = LoggerFactory.getLogger(BluetoothSpeakerSession.class);

    private final Device device;
    private final BluetoothSettings settings;
    private final BluetoothProperties properties;
    private final BluezClient bluez;
    private final Consumer<DeviceState> onChange;
    private final ScheduledExecutorService loop;

    private volatile DeviceState state = DeviceState.initial();
    private volatile int volume;
    private volatile boolean muted;
    private volatile boolean closed;
    private ScheduledFuture<?> nextPoll;      // loop thread only
    private boolean lookedOnce;                // loop thread only

    public BluetoothSpeakerSession(Device device, BluetoothProperties properties, BluezClient bluez,
                                   Consumer<DeviceState> onChange) {
        this.device = device;
        this.settings = BluetoothSettings.of(device);
        this.properties = properties;
        this.bluez = bluez;
        this.onChange = onChange;
        this.volume = properties.defaultVolume();
        this.loop = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("bluetooth-" + device.id()).factory());
    }

    public void start() {
        onChange.accept(state);
        loop.execute(this::poll);
    }

    @Override
    public DeviceState state() {
        return state;
    }

    @Override
    public void execute(Action action) {
        if (closed) {
            throw new DeviceOfflineException(device.name() + " is not connected");
        }
        throw new UnsupportedActionException(device.name() + ": playback through the server is not available yet");
    }

    @Override
    public void close() {
        closed = true;
        loop.shutdownNow();
    }

    /** Forces an immediate re-check instead of waiting for the next scheduled poll (setup page actions). */
    void pollNow() {
        if (!closed) {
            loop.execute(this::poll);
        }
    }

    private void poll() {
        if (closed) {
            return;
        }
        try {
            readState();
        } catch (RuntimeException e) {
            log.warn("Reading the state of {} failed", device.name(), e);
        } finally {
            if (!closed) {
                if (nextPoll != null) {
                    nextPoll.cancel(false);
                }
                nextPoll = loop.schedule(this::poll, nextPollSeconds(), TimeUnit.SECONDS);
            }
        }
    }

    /** Task 3 makes this depend on the player. */
    private long nextPollSeconds() {
        return properties.pollIntervalSeconds();
    }

    private void readState() {
        DeviceStatus status = bluetoothStatus();
        publish(state.withStatus(status).withPower(status == DeviceStatus.CONNECTED).withVolume(volume, 100, muted));
    }

    private DeviceStatus bluetoothStatus() {
        // Auto-connect only on the very first look after start: never page a switched-off speaker
        // repeatedly, and never reconnect behind the back of a poll that just saw it go away.
        boolean firstLook = !lookedOnce;
        lookedOnce = true;
        try {
            Optional<BluetoothDeviceInfo> info = bluez.device(settings.adapter(), settings.address());
            if (info.isEmpty() || !info.get().paired()) {
                return DeviceStatus.UNPAIRED;
            }
            if (info.get().connected()) {
                return DeviceStatus.CONNECTED;
            }
            if (properties.autoConnect() && firstLook) {
                bluez.connect(settings.adapter(), settings.address());
                return DeviceStatus.CONNECTED;
            }
            return DeviceStatus.DISCONNECTED;
        } catch (BluezException e) {
            log.debug("{}: {}", device.name(), e.getMessage());
            return DeviceStatus.DISCONNECTED;
        }
    }

    private void publish(DeviceState next) {
        if (!next.sameIgnoringTime(state)) {
            state = next;
            onChange.accept(next);
        }
    }
}

package dev.andre.homecontrol.adapters.bluetooth;

import dev.andre.homecontrol.adapters.bluetooth.bluez.BluetoothAdapterInfo;
import dev.andre.homecontrol.adapters.bluetooth.bluez.BluezClient;
import dev.andre.homecontrol.adapters.bluetooth.bluez.BluezException;
import dev.andre.homecontrol.adapters.bluetooth.bluez.BluezFailure;
import dev.andre.homecontrol.adapters.bluetooth.bluez.BluezFailures;
import dev.andre.homecontrol.adapters.bluetooth.player.AudioDevice;
import dev.andre.homecontrol.adapters.bluetooth.player.AudioDevices;
import dev.andre.homecontrol.adapters.bluetooth.player.MpvLauncher;
import dev.andre.homecontrol.adapters.bluetooth.player.MpvNotInstalledException;
import dev.andre.homecontrol.adapters.bluetooth.player.StreamRedaction;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * The checks the setup page shows under "Bluetooth speakers": is the D-Bus socket there, does
 * BlueZ answer, is there a usable adapter, is mpv installed, is there an audio output for it.
 * Cached for {@code hostCheckCacheSeconds} so the setup page does not hit D-Bus/mpv on every render.
 */
public class BluetoothHostChecks {

    private static final String BLUEZ_ID = "bluez";
    private static final String BLUEZ_LABEL = "BlueZ";
    private static final String DBUS_SOCKET_ID = "dbus-socket";
    private static final String DBUS_SOCKET_LABEL = "D-Bus system socket";
    private static final String ADAPTER_CHECK_ID = "adapter";
    private static final String ADAPTER_CHECK_LABEL = "Bluetooth adapter";
    private static final String MPV_LABEL = "mpv player";
    private static final String AUDIO_OUTPUT_ID = "audio-output";
    private static final String AUDIO_OUTPUT_LABEL = "Audio output";

    private final BluetoothProperties properties;
    private final BluezClient bluez;
    private final MpvLauncher launcher;
    private final Clock clock;
    private final UnaryOperator<String> environment;

    private List<HostCheck> cached;
    private Instant cachedAt = Instant.MIN;

    public BluetoothHostChecks(BluetoothProperties properties, BluezClient bluez, MpvLauncher launcher, Clock clock) {
        this(properties, bluez, launcher, clock, System::getenv);
    }

    BluetoothHostChecks(BluetoothProperties properties, BluezClient bluez, MpvLauncher launcher, Clock clock,
                        UnaryOperator<String> environment) {
        this.properties = properties;
        this.bluez = bluez;
        this.launcher = launcher;
        this.clock = clock;
        this.environment = environment;
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
            bluezCheck = new HostCheck(BLUEZ_ID, BLUEZ_LABEL, false, "Needs the D-Bus socket first");
        } else {
            try {
                // Fetched once and reused by the adapter check below: BlueZ answering IS the list.
                adapters = bluez.adapters();
                bluezCheck = new HostCheck(BLUEZ_ID, BLUEZ_LABEL, true, "BlueZ answered on the system bus");
            } catch (BluezException e) {
                bluezCheck = new HostCheck(BLUEZ_ID, BLUEZ_LABEL, false, e.getMessage());
            }
        }
        HostCheck adapter = adapterCheck(bluezCheck.ok(), adapters);
        HostCheck mpv = mpvCheck();
        HostCheck audioOutput = audioOutputCheck(mpv.ok());
        return List.of(socket, bluezCheck, adapter, mpv, audioOutput);
    }

    private HostCheck socketCheck() {
        Optional<Path> socket = properties.dbusSocketPath();
        if (socket.isEmpty()) {
            return new HostCheck(DBUS_SOCKET_ID, DBUS_SOCKET_LABEL, true, "Using " + properties.dbusAddress());
        }
        if (Files.exists(socket.get())) {
            return new HostCheck(DBUS_SOCKET_ID, DBUS_SOCKET_LABEL, true, "Found " + socket.get());
        }
        return new HostCheck(DBUS_SOCKET_ID, DBUS_SOCKET_LABEL, false, BluezFailures.noSocket(socket.get()));
    }

    private HostCheck adapterCheck(boolean bluezOk, List<BluetoothAdapterInfo> adapters) {
        if (!bluezOk) {
            return new HostCheck(ADAPTER_CHECK_ID, ADAPTER_CHECK_LABEL, false, "Needs BlueZ first");
        }
        if (adapters.isEmpty()) {
            return new HostCheck(ADAPTER_CHECK_ID, ADAPTER_CHECK_LABEL, false,
                    BluezFailures.message(BluezFailure.NO_ADAPTER, null));
        }
        Optional<BluetoothAdapterInfo> selected = BluetoothAdapterInfo.select(adapters, properties.adapter());
        if (selected.isEmpty()) {
            return new HostCheck(ADAPTER_CHECK_ID, ADAPTER_CHECK_LABEL, false,
                    "Adapter " + properties.adapter() + " not found; the host has " + BluetoothAdapterInfo.describe(adapters));
        }
        BluetoothAdapterInfo adapter = selected.get();
        if (!adapter.powered()) {
            return new HostCheck(ADAPTER_CHECK_ID, ADAPTER_CHECK_LABEL, false, adapter.id() + " (" + adapter.address()
                    + ") is powered off. Scanning switches it on; if that fails run rfkill unblock bluetooth on the host.");
        }
        return new HostCheck(ADAPTER_CHECK_ID, ADAPTER_CHECK_LABEL, true, adapter.id() + " (" + adapter.address() + ")");
    }

    private HostCheck mpvCheck() {
        try {
            String output = launcher.run(List.of("--no-config", "--version"), Duration.ofSeconds(5));
            String firstLine = output.lines().filter(line -> !line.isBlank()).findFirst().orElse("").strip();
            int copyright = firstLine.indexOf(" Copyright");
            String version = copyright >= 0 ? firstLine.substring(0, copyright) : firstLine;
            return new HostCheck("mpv", MPV_LABEL, true, version);
        } catch (MpvNotInstalledException _) {
            return new HostCheck("mpv", MPV_LABEL, false, BluetoothSpeakerSession.MPV_MISSING);
        } catch (IOException e) {
            return new HostCheck("mpv", MPV_LABEL, false, "mpv did not run: " + StreamRedaction.redact(e.getMessage()));
        }
    }

    private HostCheck audioOutputCheck(boolean mpvOk) {
        String template = properties.audioDeviceTemplate();
        if (!template.isBlank()) {
            return new HostCheck(AUDIO_OUTPUT_ID, AUDIO_OUTPUT_LABEL, true, "Using the template " + template);
        }
        if (!mpvOk) {
            return new HostCheck(AUDIO_OUTPUT_ID, AUDIO_OUTPUT_LABEL, false, "Needs mpv first");
        }
        try {
            String help = launcher.run(List.of("--no-config", "--audio-device=help"), Duration.ofSeconds(5));
            List<AudioDevice> outputs = AudioDevices.soundServerOutputs(AudioDevices.parse(help));
            if (!outputs.isEmpty()) {
                return new HostCheck(AUDIO_OUTPUT_ID, AUDIO_OUTPUT_LABEL, true,
                        "PipeWire or PulseAudio reachable (" + outputs.size() + " outputs)");
            }
            StringBuilder detail = new StringBuilder("No PipeWire or PulseAudio server is reachable from the container. "
                    + "Mount the audio user's /run/user/<uid>/pulse to /run/pulse and set "
                    + "PULSE_SERVER=unix:/run/pulse/native (see docs/bluetooth-speakers.md).");
            String pulseServer = environment.apply("PULSE_SERVER");
            if (pulseServer != null) {
                detail.append(" PULSE_SERVER is ").append(pulseServer).append(", but nothing answers there.");
            }
            return new HostCheck(AUDIO_OUTPUT_ID, AUDIO_OUTPUT_LABEL, false, detail.toString());
        } catch (IOException e) {
            return new HostCheck(AUDIO_OUTPUT_ID, AUDIO_OUTPUT_LABEL, false, "mpv did not run: " + StreamRedaction.redact(e.getMessage()));
        }
    }
}

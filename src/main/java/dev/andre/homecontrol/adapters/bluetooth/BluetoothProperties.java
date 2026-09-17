package dev.andre.homecontrol.adapters.bluetooth;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.nio.file.Path;
import java.util.Optional;

/**
 * {@code home-control.bluetooth.*}. Off by default: the module needs the host's D-Bus socket,
 * BlueZ, a Bluetooth adapter, an audio server and mpv (see docs/bluetooth-speakers.md). A bad
 * value fails startup instead of surfacing later as a busy loop or a silent no-op, as with the
 * other adapter modules' {@code *Properties}.
 */
@ConfigurationProperties("home-control.bluetooth")
@Validated
public record BluetoothProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue(DEFAULT_DBUS_ADDRESS) @NotBlank String dbusAddress,
        String adapter,
        @DefaultValue("10") @Positive int scanSeconds,
        @DefaultValue("45") @Positive int bluezTimeoutSeconds,
        @DefaultValue("5") @Positive int pollIntervalSeconds,
        @DefaultValue("1") @Positive int playingPollIntervalSeconds,
        @DefaultValue("true") boolean autoConnect,
        @DefaultValue("mpv") @NotBlank String mpvPath,
        Path runtimeDir,
        String audioDeviceTemplate,
        @DefaultValue("50") @Min(1) @Max(100) int defaultVolume,
        @DefaultValue("5") @Positive int playerStartTimeoutSeconds,
        @DefaultValue("15") @Positive int loadTimeoutSeconds,
        @DefaultValue("3") @Positive int commandTimeoutSeconds,
        @DefaultValue("30") @Positive int hostCheckCacheSeconds) {

    public static final String DEFAULT_DBUS_ADDRESS = "unix:path=/run/dbus/system_bus_socket";

    private static final Path DEFAULT_RUNTIME_DIR = Path.of(System.getProperty("java.io.tmpdir"), "home-control-bluetooth");

    public BluetoothProperties {
        adapter = adapter == null ? "" : adapter.strip();
        audioDeviceTemplate = audioDeviceTemplate == null ? "" : audioDeviceTemplate.strip();
    }

    public static BluetoothProperties defaults() {
        return new BluetoothProperties(false, DEFAULT_DBUS_ADDRESS, "", 10, 45, 5, 1, true, "mpv", DEFAULT_RUNTIME_DIR,
                "", 50, 5, 15, 3, 30);
    }

    /** {@code runtimeDir} defaults to {@code <java.io.tmpdir>/home-control-bluetooth}; not expressible as a static default. */
    @Override
    public Path runtimeDir() {
        return runtimeDir == null ? DEFAULT_RUNTIME_DIR : runtimeDir;
    }

    /** The socket file of a {@code unix:path=…} address; empty for other transports. */
    public Optional<Path> dbusSocketPath() {
        if (!dbusAddress.startsWith("unix:")) {
            return Optional.empty();
        }
        for (String part : dbusAddress.substring("unix:".length()).split(",")) {
            if (part.startsWith("path=")) {
                return Optional.of(Path.of(part.substring("path=".length())));
            }
        }
        return Optional.empty();
    }

    public BluetoothProperties withDbusAddress(String value) {
        return new BluetoothProperties(enabled, value, adapter, scanSeconds, bluezTimeoutSeconds, pollIntervalSeconds,
                playingPollIntervalSeconds, autoConnect, mpvPath, runtimeDir, audioDeviceTemplate, defaultVolume,
                playerStartTimeoutSeconds, loadTimeoutSeconds, commandTimeoutSeconds, hostCheckCacheSeconds);
    }

    public BluetoothProperties withAdapter(String value) {
        return new BluetoothProperties(enabled, dbusAddress, value, scanSeconds, bluezTimeoutSeconds, pollIntervalSeconds,
                playingPollIntervalSeconds, autoConnect, mpvPath, runtimeDir, audioDeviceTemplate, defaultVolume,
                playerStartTimeoutSeconds, loadTimeoutSeconds, commandTimeoutSeconds, hostCheckCacheSeconds);
    }

    public BluetoothProperties withScanSeconds(int value) {
        return new BluetoothProperties(enabled, dbusAddress, adapter, value, bluezTimeoutSeconds, pollIntervalSeconds,
                playingPollIntervalSeconds, autoConnect, mpvPath, runtimeDir, audioDeviceTemplate, defaultVolume,
                playerStartTimeoutSeconds, loadTimeoutSeconds, commandTimeoutSeconds, hostCheckCacheSeconds);
    }

    public BluetoothProperties withAutoConnect(boolean value) {
        return new BluetoothProperties(enabled, dbusAddress, adapter, scanSeconds, bluezTimeoutSeconds, pollIntervalSeconds,
                playingPollIntervalSeconds, value, mpvPath, runtimeDir, audioDeviceTemplate, defaultVolume,
                playerStartTimeoutSeconds, loadTimeoutSeconds, commandTimeoutSeconds, hostCheckCacheSeconds);
    }

    public BluetoothProperties withMpvPath(String value) {
        return new BluetoothProperties(enabled, dbusAddress, adapter, scanSeconds, bluezTimeoutSeconds, pollIntervalSeconds,
                playingPollIntervalSeconds, autoConnect, value, runtimeDir, audioDeviceTemplate, defaultVolume,
                playerStartTimeoutSeconds, loadTimeoutSeconds, commandTimeoutSeconds, hostCheckCacheSeconds);
    }

    public BluetoothProperties withRuntimeDir(Path value) {
        return new BluetoothProperties(enabled, dbusAddress, adapter, scanSeconds, bluezTimeoutSeconds, pollIntervalSeconds,
                playingPollIntervalSeconds, autoConnect, mpvPath, value, audioDeviceTemplate, defaultVolume,
                playerStartTimeoutSeconds, loadTimeoutSeconds, commandTimeoutSeconds, hostCheckCacheSeconds);
    }

    public BluetoothProperties withAudioDeviceTemplate(String value) {
        return new BluetoothProperties(enabled, dbusAddress, adapter, scanSeconds, bluezTimeoutSeconds, pollIntervalSeconds,
                playingPollIntervalSeconds, autoConnect, mpvPath, runtimeDir, value, defaultVolume,
                playerStartTimeoutSeconds, loadTimeoutSeconds, commandTimeoutSeconds, hostCheckCacheSeconds);
    }

    public BluetoothProperties withTimings(int poll, int playingPoll, int playerStart, int load, int command) {
        return new BluetoothProperties(enabled, dbusAddress, adapter, scanSeconds, bluezTimeoutSeconds, poll,
                playingPoll, autoConnect, mpvPath, runtimeDir, audioDeviceTemplate, defaultVolume,
                playerStart, load, command, hostCheckCacheSeconds);
    }
}

package dev.andre.homecontrol.adapters.bluetooth;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.validation.annotation.Validated;

import java.nio.file.Path;
import java.util.Optional;

/**
 * {@code home-control.bluetooth.*}. Off by default: the module needs the host's D-Bus socket,
 * BlueZ, a Bluetooth adapter, an audio server and mpv (see docs/user/bluetooth-speakers.md). A bad
 * value fails startup instead of surfacing later as a busy loop or a silent no-op, as with the
 * other adapter modules' {@code *Properties}.
 */
@ConfigurationProperties("home-control.bluetooth")
@Validated
public record BluetoothProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue(DEFAULT_DBUS_ADDRESS) @NotBlank String dbusAddress,
        String adapter,
        @DefaultValue("10s") @DurationUnit(ChronoUnit.SECONDS) @DurationMin(nanos = 1) Duration scanDuration,
        @DefaultValue("45s") @DurationUnit(ChronoUnit.SECONDS) @DurationMin(nanos = 1) Duration bluezTimeout,
        @DefaultValue("5s") @DurationUnit(ChronoUnit.SECONDS) @DurationMin(nanos = 1) Duration pollInterval,
        @DefaultValue("1s") @DurationUnit(ChronoUnit.SECONDS) @DurationMin(nanos = 1) Duration playingPollInterval,
        @DefaultValue("true") boolean autoConnect,
        @DefaultValue("mpv") @NotBlank String mpvPath,
        Path runtimeDir,
        String audioDeviceTemplate,
        @DefaultValue("50") @Min(1) @Max(100) int defaultVolume,
        @DefaultValue("5s") @DurationUnit(ChronoUnit.SECONDS) @DurationMin(nanos = 1) Duration playerStartTimeout,
        @DefaultValue("15s") @DurationUnit(ChronoUnit.SECONDS) @DurationMin(nanos = 1) Duration loadTimeout,
        @DefaultValue("3s") @DurationUnit(ChronoUnit.SECONDS) @DurationMin(nanos = 1) Duration commandTimeout,
        @DefaultValue("30s") @DurationUnit(ChronoUnit.SECONDS) @DurationMin(nanos = 1) Duration hostCheckCacheTtl) {

    public static final String DEFAULT_DBUS_ADDRESS = "unix:path=/run/dbus/system_bus_socket";

    private static final Path DEFAULT_RUNTIME_DIR = Path.of(System.getProperty("java.io.tmpdir"), "home-control-bluetooth");

    public BluetoothProperties {
        adapter = adapter == null ? "" : adapter.strip();
        audioDeviceTemplate = audioDeviceTemplate == null ? "" : audioDeviceTemplate.strip();
    }

    public static BluetoothProperties defaults() {
        return new BluetoothProperties(false, DEFAULT_DBUS_ADDRESS, "", Duration.ofSeconds(10), Duration.ofSeconds(45),
                Duration.ofSeconds(5), Duration.ofSeconds(1), true, "mpv", DEFAULT_RUNTIME_DIR, "", 50,
                Duration.ofSeconds(5), Duration.ofSeconds(15), Duration.ofSeconds(3), Duration.ofSeconds(30));
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
        return new BluetoothProperties(enabled, value, adapter, scanDuration, bluezTimeout, pollInterval,
                playingPollInterval, autoConnect, mpvPath, runtimeDir, audioDeviceTemplate, defaultVolume,
                playerStartTimeout, loadTimeout, commandTimeout, hostCheckCacheTtl);
    }

    public BluetoothProperties withAdapter(String value) {
        return new BluetoothProperties(enabled, dbusAddress, value, scanDuration, bluezTimeout, pollInterval,
                playingPollInterval, autoConnect, mpvPath, runtimeDir, audioDeviceTemplate, defaultVolume,
                playerStartTimeout, loadTimeout, commandTimeout, hostCheckCacheTtl);
    }

    public BluetoothProperties withScanDuration(Duration value) {
        return new BluetoothProperties(enabled, dbusAddress, adapter, value, bluezTimeout, pollInterval,
                playingPollInterval, autoConnect, mpvPath, runtimeDir, audioDeviceTemplate, defaultVolume,
                playerStartTimeout, loadTimeout, commandTimeout, hostCheckCacheTtl);
    }

    public BluetoothProperties withAutoConnect(boolean value) {
        return new BluetoothProperties(enabled, dbusAddress, adapter, scanDuration, bluezTimeout, pollInterval,
                playingPollInterval, value, mpvPath, runtimeDir, audioDeviceTemplate, defaultVolume,
                playerStartTimeout, loadTimeout, commandTimeout, hostCheckCacheTtl);
    }

    public BluetoothProperties withMpvPath(String value) {
        return new BluetoothProperties(enabled, dbusAddress, adapter, scanDuration, bluezTimeout, pollInterval,
                playingPollInterval, autoConnect, value, runtimeDir, audioDeviceTemplate, defaultVolume,
                playerStartTimeout, loadTimeout, commandTimeout, hostCheckCacheTtl);
    }

    public BluetoothProperties withRuntimeDir(Path value) {
        return new BluetoothProperties(enabled, dbusAddress, adapter, scanDuration, bluezTimeout, pollInterval,
                playingPollInterval, autoConnect, mpvPath, value, audioDeviceTemplate, defaultVolume,
                playerStartTimeout, loadTimeout, commandTimeout, hostCheckCacheTtl);
    }

    public BluetoothProperties withAudioDeviceTemplate(String value) {
        return new BluetoothProperties(enabled, dbusAddress, adapter, scanDuration, bluezTimeout, pollInterval,
                playingPollInterval, autoConnect, mpvPath, runtimeDir, value, defaultVolume,
                playerStartTimeout, loadTimeout, commandTimeout, hostCheckCacheTtl);
    }

    public BluetoothProperties withTimings(Duration poll, Duration playingPoll, Duration playerStart, Duration load,
                                           Duration command) {
        return new BluetoothProperties(enabled, dbusAddress, adapter, scanDuration, bluezTimeout, poll,
                playingPoll, autoConnect, mpvPath, runtimeDir, audioDeviceTemplate, defaultVolume,
                playerStart, load, command, hostCheckCacheTtl);
    }
}

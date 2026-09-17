package dev.andre.homecontrol.adapters.bluetooth;

import dev.andre.homecontrol.adapters.bluetooth.bluez.BluezClient;
import dev.andre.homecontrol.adapters.bluetooth.bluez.BluezException;
import dev.andre.homecontrol.adapters.bluetooth.bluez.BluezFailure;
import dev.andre.homecontrol.adapters.bluetooth.player.MpvLauncher;
import dev.andre.homecontrol.adapters.bluetooth.player.ProcessMpvLauncher;
import dev.andre.homecontrol.device.DeviceManager;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * The module is off unless {@code home-control.bluetooth.enabled=true}: no bean, no D-Bus, no host
 * requirement. {@link BluetoothProperties} follows the house convention (dispatch-common.md) of
 * {@code @Validated} + jakarta constraints rather than hand-rolled clamping: a bad value fails
 * startup instead of silently being replaced.
 */
class BluetoothModuleSwitchTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(DeviceManager.class, () -> mock(DeviceManager.class))
            .withUserConfiguration(BluetoothConfiguration.class);

    @Test
    void isOffByDefault() {
        runner.run(context -> {
            assertThat(context.getBeansOfType(BluetoothProperties.class)).isEmpty();
            assertThat(context.getBeansOfType(BluezClient.class)).isEmpty();
            assertThat(context.getBeansOfType(BluetoothSpeakerAdapter.class)).isEmpty();
            assertThat(context.getBeansOfType(BluetoothPairingService.class)).isEmpty();
            assertThat(context.getBeansOfType(BluetoothHostChecks.class)).isEmpty();
            assertThat(context.getBeansOfType(MpvLauncher.class)).isEmpty();
        });
    }

    @Test
    void canBeSwitchedOn() {
        runner.withPropertyValues("home-control.bluetooth.enabled=true",
                        "home-control.bluetooth.dbus-address=unix:path=/nonexistent/hc-bus.sock")
                .run(context -> {
                    assertThat(context).hasSingleBean(BluetoothProperties.class)
                            .hasSingleBean(BluezClient.class)
                            .hasSingleBean(BluetoothSpeakerAdapter.class)
                            .hasSingleBean(BluetoothPairingService.class)
                            .hasSingleBean(BluetoothHostChecks.class)
                            .hasSingleBean(MpvLauncher.class);
                    assertThat(context.getBean(MpvLauncher.class)).isInstanceOf(ProcessMpvLauncher.class);
                    BluetoothProperties properties = context.getBean(BluetoothProperties.class);
                    assertThat(properties.dbusAddress()).isEqualTo("unix:path=/nonexistent/hc-bus.sock");
                    assertThat(properties.scanSeconds()).isEqualTo(10);
                    assertThat(properties.defaultVolume()).isEqualTo(50);
                    assertThat(properties.mpvPath()).isEqualTo("mpv");
                    assertThat(properties.runtimeDir())
                            .isEqualTo(Path.of(System.getProperty("java.io.tmpdir"), "home-control-bluetooth"));
                    // The context started although no D-Bus socket exists; only using the client fails.
                    assertThatThrownBy(() -> context.getBean(BluezClient.class).adapters())
                            .isInstanceOf(BluezException.class)
                            .extracting(e -> ((BluezException) e).failure()).isEqualTo(BluezFailure.NO_DBUS_SOCKET);
                });
    }

    @Test
    void applicationYamlKeepsItOff() throws Exception {
        assertThat(bluetoothEnabled("src/main/resources/application.yaml")).isEqualTo(false);
        assertThat(bluetoothEnabled("src/test/resources/application.yaml")).isEqualTo(false);
    }

    @Test
    void anInvalidScanSecondsFailsStartup() {
        runner.withPropertyValues("home-control.bluetooth.enabled=true", "home-control.bluetooth.scan-seconds=0")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void anOutOfRangeVolumeFailsStartup() {
        runner.withPropertyValues("home-control.bluetooth.enabled=true", "home-control.bluetooth.default-volume=0")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void withMethodsReplaceASingleField() {
        BluetoothProperties base = BluetoothProperties.defaults();
        assertThat(base.withScanSeconds(1).scanSeconds()).isEqualTo(1);
        assertThat(base.withAdapter("hci1").adapter()).isEqualTo("hci1");
        assertThat(base.withAutoConnect(false).autoConnect()).isFalse();
        assertThat(base.withMpvPath("/usr/bin/mpv").mpvPath()).isEqualTo("/usr/bin/mpv");
        assertThat(base.withRuntimeDir(Path.of("/tmp/x")).runtimeDir()).isEqualTo(Path.of("/tmp/x"));
        assertThat(base.withAudioDeviceTemplate("alsa/x").audioDeviceTemplate()).isEqualTo("alsa/x");
        BluetoothProperties timed = base.withTimings(2, 3, 4, 5, 6);
        assertThat(timed.pollIntervalSeconds()).isEqualTo(2);
        assertThat(timed.playingPollIntervalSeconds()).isEqualTo(3);
        assertThat(timed.playerStartTimeoutSeconds()).isEqualTo(4);
        assertThat(timed.loadTimeoutSeconds()).isEqualTo(5);
        assertThat(timed.commandTimeoutSeconds()).isEqualTo(6);
    }

    @Test
    void findsTheSocketOfAUnixAddress() {
        assertThat(BluetoothProperties.defaults().dbusSocketPath()).contains(Path.of("/run/dbus/system_bus_socket"));
        assertThat(BluetoothProperties.defaults().withDbusAddress("unix:path=/tmp/x.sock,guid=1234").dbusSocketPath())
                .contains(Path.of("/tmp/x.sock"));
        assertThat(BluetoothProperties.defaults().withDbusAddress("tcp:host=localhost,port=1234").dbusSocketPath())
                .isEmpty();
    }

    @SuppressWarnings("unchecked")
    private static boolean bluetoothEnabled(String path) throws Exception {
        Map<String, Object> yaml;
        try (InputStream input = Files.newInputStream(Path.of(path))) {
            yaml = new Yaml().load(input);
        }
        Map<String, Object> homeControl = (Map<String, Object>) yaml.get("home-control");
        Map<String, Object> bluetooth = (Map<String, Object>) homeControl.get("bluetooth");
        return (boolean) bluetooth.get("enabled");
    }
}

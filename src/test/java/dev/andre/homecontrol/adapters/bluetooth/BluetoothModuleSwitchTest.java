package dev.andre.homecontrol.adapters.bluetooth;

import dev.andre.homecontrol.core.DeviceEnrollment;
import dev.andre.homecontrol.core.DeviceQueries;
import dev.andre.homecontrol.adapters.bluetooth.bluez.BluezClient;
import dev.andre.homecontrol.adapters.bluetooth.bluez.BluezException;
import dev.andre.homecontrol.adapters.bluetooth.bluez.BluezFailure;
import dev.andre.homecontrol.adapters.bluetooth.player.MpvLauncher;
import dev.andre.homecontrol.adapters.bluetooth.player.ProcessMpvLauncher;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Path;

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
            .withBean(DeviceQueries.class, () -> mock(DeviceQueries.class))
            .withBean(DeviceEnrollment.class, () -> mock(DeviceEnrollment.class))
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
                    assertThat(properties.scanDuration()).isEqualTo(Duration.ofSeconds(10));
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
    void anInvalidScanSecondsFailsStartup() {
        runner.withPropertyValues("home-control.bluetooth.enabled=true", "home-control.bluetooth.scan-duration=0s")
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
        assertThat(base.withScanDuration(Duration.ofSeconds(1)).scanDuration()).isEqualTo(Duration.ofSeconds(1));
        assertThat(base.withAdapter("hci1").adapter()).isEqualTo("hci1");
        assertThat(base.withAutoConnect(false).autoConnect()).isFalse();
        assertThat(base.withMpvPath("/usr/bin/mpv").mpvPath()).isEqualTo("/usr/bin/mpv");
        assertThat(base.withRuntimeDir(Path.of("/tmp/x")).runtimeDir()).isEqualTo(Path.of("/tmp/x"));
        assertThat(base.withAudioDeviceTemplate("alsa/x").audioDeviceTemplate()).isEqualTo("alsa/x");
        BluetoothProperties timed = base.withTimings(Duration.ofSeconds(2), Duration.ofSeconds(3),
                Duration.ofSeconds(4),
                Duration.ofSeconds(5), Duration.ofSeconds(6));
        assertThat(timed.pollInterval()).isEqualTo(Duration.ofSeconds(2));
        assertThat(timed.playingPollInterval()).isEqualTo(Duration.ofSeconds(3));
        assertThat(timed.playerStartTimeout()).isEqualTo(Duration.ofSeconds(4));
        assertThat(timed.loadTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(timed.commandTimeout()).isEqualTo(Duration.ofSeconds(6));
    }

    @Test
    void findsTheSocketOfAUnixAddress() {
        assertThat(BluetoothProperties.defaults().dbusSocketPath()).contains(Path.of("/run/dbus/system_bus_socket"));
        assertThat(BluetoothProperties.defaults().withDbusAddress("unix:path=/tmp/x.sock,guid=1234").dbusSocketPath())
                .contains(Path.of("/tmp/x.sock"));
        assertThat(BluetoothProperties.defaults().withDbusAddress("tcp:host=localhost,port=1234").dbusSocketPath())
                .isEmpty();
    }

}

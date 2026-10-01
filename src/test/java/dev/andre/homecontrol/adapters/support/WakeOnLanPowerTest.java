package dev.andre.homecontrol.adapters.support;

import dev.andre.homecontrol.adapters.net.FakeWakeOnLanReceiver;
import dev.andre.homecontrol.adapters.net.WakeOnLan;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.core.DeviceOfflineException;
import dev.andre.homecontrol.core.WakeOnLanSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WakeOnLanPowerTest {

    private static final String MAC = "A8:23:FE:01:02:03";

    private FakeWakeOnLanReceiver receiver;
    private final AtomicReference<Device> device = new AtomicReference<>(tv(Map.of()));

    @BeforeEach
    void startReceiver() throws IOException {
        receiver = new FakeWakeOnLanReceiver();
    }

    @AfterEach
    void stopReceiver() {
        receiver.close();
    }

    private static Device tv(Map<String, String> settings) {
        return new Device("lg", "LG TV", DeviceKind.WEBOS, "192.0.2.10", Map.of("webos", settings), Instant.now());
    }

    private WakeOnLanPower power() {
        return new WakeOnLanPower("LG TV", device::get, "webos", new WakeOnLan(receiver.address()));
    }

    @Test
    void sendsTheMagicPacketToTheMacInTheSettings() throws Exception {
        device.set(tv(Map.of(WakeOnLanSettings.MAC_ADDRESS, MAC)));

        power().wake();

        assertThat(receiver.nextPacket()).containsExactly(WakeOnLan.magicPacket(MAC));
    }

    @Test
    void readsTheSettingsWhenWakingNotWhenBuilt() throws Exception {
        WakeOnLanPower power = power();
        device.set(tv(Map.of(WakeOnLanSettings.MAC_ADDRESS, MAC)));

        power.wake();

        assertThat(receiver.nextPacket()).containsExactly(WakeOnLan.magicPacket(MAC));
    }

    @Test
    void withoutAMacItExplainsTheFixAndSendsNothing() {
        WakeOnLanPower power = power();

        assertThatThrownBy(power::wake)
                .isInstanceOf(DeviceOfflineException.class)
                .hasMessage("LG TV is off and no MAC address is known for Wake-on-LAN; switch it on once by hand or"
                        + " enter its MAC address on the setup page");
        assertThat(receiver.received()).isZero();
    }

    @Test
    void aPacketThatCannotBeBuiltIsAnOfflineDeviceWithTheReason() {
        device.set(tv(Map.of(WakeOnLanSettings.MAC_ADDRESS, "not a MAC")));
        WakeOnLanPower power = power();

        assertThatThrownBy(power::wake)
                .isInstanceOf(DeviceOfflineException.class)
                .hasMessageStartingWith("Could not send the Wake-on-LAN packet: ");
    }
}

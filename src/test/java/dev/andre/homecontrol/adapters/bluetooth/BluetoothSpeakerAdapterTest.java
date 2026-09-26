package dev.andre.homecontrol.adapters.bluetooth;

import dev.andre.homecontrol.adapters.bluetooth.bluez.BluetoothDeviceInfo;
import dev.andre.homecontrol.adapters.bluetooth.bluez.FakeBluezClient;
import dev.andre.homecontrol.adapters.bluetooth.player.InProcessMpvLauncher;
import dev.andre.homecontrol.core.Capability;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceHandle;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.core.DeviceState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static dev.andre.homecontrol.adapters.bluetooth.bluez.BluezFailure.BLUEZ_NOT_RUNNING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.awaitility.Awaitility.await;

class BluetoothSpeakerAdapterTest {

    private final FakeBluezClient bluez = new FakeBluezClient();
    private final InProcessMpvLauncher launcher = new InProcessMpvLauncher();
    private final BluetoothSpeakerAdapter adapter = new BluetoothSpeakerAdapter(BluetoothProperties.defaults(), bluez, launcher);

    @AfterEach
    void tearDown() {
        launcher.close();
    }

    private Device speaker(String address) {
        return new Device(BluetoothSettings.deviceId(address), "JBL Flip 5", DeviceKind.BLUETOOTH, address,
                Map.of(BluetoothSettings.ADAPTER_ID, new BluetoothSettings(address, FakeBluezClient.ADAPTER, "").toMap()),
                Instant.now());
    }

    @Test
    void declaresALocalAudioSink() {
        Device device = speaker("AA:BB:CC:DD:EE:FF");
        assertThat(adapter.id()).isEqualTo("bluetooth");
        assertThat(adapter.kind()).isEqualTo(DeviceKind.BLUETOOTH);
        assertThat(adapter.capabilities(device)).containsExactlyInAnyOrder(Capability.LOCAL_AUDIO_SINK, Capability.VOLUME);
        assertThat(adapter.discovered()).isEmpty();
        assertThat(adapter.settingsFor(null)).isEmpty();
    }

    @Test
    void forgetUnpairsOnTheHost() {
        Device device = speaker("AA:BB:CC:DD:EE:FF");
        bluez.known("AA:BB:CC:DD:EE:FF", "JBL Flip 5");
        adapter.forget(device);
        assertThat(bluez.calls()).contains("remove AA:BB:CC:DD:EE:FF");
    }

    @Test
    void forgetNeverFails() {
        Device device = speaker("AA:BB:CC:DD:EE:FF");
        bluez.unavailable(BLUEZ_NOT_RUNNING);
        assertThatCode(() -> adapter.forget(device)).doesNotThrowAnyException();
    }

    @Test
    void connectStartsASession() {
        Device device = speaker("AA:BB:CC:DD:EE:FF");
        bluez.known("AA:BB:CC:DD:EE:FF", "JBL Flip 5").paired(true).connected(true)
                .uuids(BluetoothDeviceInfo.A2DP_SINK);
        List<DeviceState> states = new CopyOnWriteArrayList<>();
        DeviceHandle handle = adapter.connect(device, states::add);
        try {
            assertThat(handle).isInstanceOf(BluetoothSpeakerSession.class);
            await().untilAsserted(() -> assertThat(states).isNotEmpty());
        } finally {
            handle.close();
        }
    }
}

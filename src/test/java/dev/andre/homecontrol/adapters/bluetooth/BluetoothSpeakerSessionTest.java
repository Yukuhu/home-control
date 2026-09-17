package dev.andre.homecontrol.adapters.bluetooth;

import dev.andre.homecontrol.adapters.bluetooth.bluez.BluetoothDeviceInfo;
import dev.andre.homecontrol.adapters.bluetooth.bluez.FakeBluezClient;
import dev.andre.homecontrol.core.Action;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.core.DeviceOfflineException;
import dev.andre.homecontrol.core.DeviceStatus;
import dev.andre.homecontrol.core.RemoteKey;
import dev.andre.homecontrol.core.UnsupportedActionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static dev.andre.homecontrol.adapters.bluetooth.bluez.BluezFailure.BLUEZ_NOT_RUNNING;
import static dev.andre.homecontrol.adapters.bluetooth.bluez.BluezFailure.UNREACHABLE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class BluetoothSpeakerSessionTest {

    private static final Duration WAIT = Duration.ofSeconds(5);

    private final FakeBluezClient bluez = new FakeBluezClient();
    private final BluetoothProperties properties = BluetoothProperties.defaults().withTimings(1, 1, 5, 5, 2);
    private final List<dev.andre.homecontrol.core.DeviceState> states = new CopyOnWriteArrayList<>();
    private Device device;
    private BluetoothSpeakerSession session;

    @BeforeEach
    void setUp() {
        device = new Device("bluetooth-aa-bb-cc-dd-ee-ff", "JBL Flip 5", DeviceKind.BLUETOOTH, "AA:BB:CC:DD:EE:FF",
                Map.of("bluetooth", new BluetoothSettings("AA:BB:CC:DD:EE:FF", FakeBluezClient.ADAPTER, "").toMap()),
                Instant.now());
    }

    @AfterEach
    void tearDown() {
        if (session != null) {
            session.close();
        }
    }

    private BluetoothSpeakerSession start() {
        session = new BluetoothSpeakerSession(device, properties, bluez, states::add);
        session.start();
        return session;
    }

    @Test
    void publishesTheInitialStateOnStart() {
        bluez.known("AA:BB:CC:DD:EE:FF", "JBL Flip 5").paired(true).connected(true).uuids(BluetoothDeviceInfo.A2DP_SINK);
        start();
        assertThat(states).isNotEmpty();
        assertThat(states.get(0).status()).isEqualTo(DeviceStatus.DISCONNECTED);
    }

    @Test
    void aConnectedSpeakerIsConnected() {
        bluez.known("AA:BB:CC:DD:EE:FF", "JBL Flip 5").paired(true).connected(true).uuids(BluetoothDeviceInfo.A2DP_SINK);
        start();
        await().atMost(WAIT).untilAsserted(() -> assertThat(session.state().status()).isEqualTo(DeviceStatus.CONNECTED));
        assertThat(session.state().powerOn()).isTrue();
        assertThat(session.state().volumeLevel()).isEqualTo(50);
        assertThat(session.state().volumeMax()).isEqualTo(100);
        assertThat(session.state().nowPlaying()).isNull();
    }

    @Test
    void aSwitchedOffSpeakerIsDisconnected() {
        bluez.known("AA:BB:CC:DD:EE:FF", "JBL Flip 5").paired(true).connected(false).uuids(BluetoothDeviceInfo.A2DP_SINK);
        BluetoothProperties noAutoConnect = properties.withAutoConnect(false);
        session = new BluetoothSpeakerSession(device, noAutoConnect, bluez, states::add);
        session.start();
        await().pollDelay(Duration.ofSeconds(2)).atMost(WAIT)
                .untilAsserted(() -> assertThat(session.state().status()).isEqualTo(DeviceStatus.DISCONNECTED));
        assertThat(bluez.calls()).noneMatch(call -> call.startsWith("connect"));
    }

    @Test
    void autoConnectTriesOnceAfterStart() {
        bluez.known("AA:BB:CC:DD:EE:FF", "JBL Flip 5").paired(true).connected(false).uuids(BluetoothDeviceInfo.A2DP_SINK);
        bluez.failNext("connect", UNREACHABLE, "br-connection-page-timeout");
        start();
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                assertThat(session.state().status()).isEqualTo(DeviceStatus.DISCONNECTED));
        assertThat(bluez.calls()).filteredOn(call -> call.equals("connect AA:BB:CC:DD:EE:FF")).hasSize(1);
    }

    @Test
    void autoConnectConnects() {
        bluez.known("AA:BB:CC:DD:EE:FF", "JBL Flip 5").paired(true).connected(false).uuids(BluetoothDeviceInfo.A2DP_SINK);
        start();
        await().atMost(WAIT).untilAsserted(() -> assertThat(session.state().status()).isEqualTo(DeviceStatus.CONNECTED));
        assertThat(bluez.calls()).contains("connect AA:BB:CC:DD:EE:FF");
    }

    @Test
    void aSpeakerUnpairedOnTheHostIsUnpaired() {
        bluez.known("AA:BB:CC:DD:EE:FF", "JBL Flip 5").paired(true).connected(true).uuids(BluetoothDeviceInfo.A2DP_SINK);
        start();
        await().atMost(WAIT).untilAsserted(() -> assertThat(session.state().status()).isEqualTo(DeviceStatus.CONNECTED));
        bluez.device("AA:BB:CC:DD:EE:FF").paired(false);
        await().atMost(WAIT).untilAsserted(() -> assertThat(session.state().status()).isEqualTo(DeviceStatus.UNPAIRED));
    }

    @Test
    void bluezTroubleIsDisconnectedNotACrash() {
        bluez.known("AA:BB:CC:DD:EE:FF", "JBL Flip 5").paired(true).connected(true).uuids(BluetoothDeviceInfo.A2DP_SINK);
        start();
        await().atMost(WAIT).untilAsserted(() -> assertThat(session.state().status()).isEqualTo(DeviceStatus.CONNECTED));
        bluez.unavailable(BLUEZ_NOT_RUNNING);
        await().atMost(WAIT).untilAsserted(() -> assertThat(session.state().status()).isEqualTo(DeviceStatus.DISCONNECTED));
        bluez.unavailable(null);
        await().atMost(WAIT).untilAsserted(() -> assertThat(session.state().status()).isEqualTo(DeviceStatus.CONNECTED));
    }

    @Test
    void publishesOnlyChanges() {
        bluez.known("AA:BB:CC:DD:EE:FF", "JBL Flip 5").paired(true).connected(true).uuids(BluetoothDeviceInfo.A2DP_SINK);
        start();
        await().atMost(WAIT).untilAsserted(() -> assertThat(session.state().status()).isEqualTo(DeviceStatus.CONNECTED));
        int size = states.size();
        await().pollDelay(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(8))
                .untilAsserted(() -> assertThat(states.size()).isEqualTo(size));
    }

    @Test
    void playbackIsNotAvailableYet() {
        bluez.known("AA:BB:CC:DD:EE:FF", "JBL Flip 5").paired(true).connected(true).uuids(BluetoothDeviceInfo.A2DP_SINK);
        start();
        assertThatThrownBy(() -> session.execute(new Action.PlayMedia(URI.create("http://127.0.0.1:9/a.mp3"),
                "audio/mpeg", "A", null)))
                .isInstanceOf(UnsupportedActionException.class).hasMessageContaining("not available yet");
        assertThatThrownBy(() -> session.execute(new Action.PressKey(RemoteKey.HOME)))
                .isInstanceOf(UnsupportedActionException.class);
    }

    @Test
    void closeStopsPolling() {
        bluez.known("AA:BB:CC:DD:EE:FF", "JBL Flip 5").paired(true).connected(true).uuids(BluetoothDeviceInfo.A2DP_SINK);
        start();
        await().atMost(WAIT).untilAsserted(() -> assertThat(session.state().status()).isEqualTo(DeviceStatus.CONNECTED));
        session.close();
        int reads = bluez.reads();
        await().pollDelay(Duration.ofMillis(2500)).atMost(Duration.ofSeconds(4))
                .untilAsserted(() -> assertThat(bluez.reads()).isEqualTo(reads));
        assertThatThrownBy(() -> session.execute(new Action.Stop())).isInstanceOf(DeviceOfflineException.class);
    }
}

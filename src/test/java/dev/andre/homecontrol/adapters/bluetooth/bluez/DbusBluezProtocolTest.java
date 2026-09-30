package dev.andre.homecontrol.adapters.bluetooth.bluez;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DbusBluezProtocolTest {
    private static final String ADAPTER = "AA:BB:CC:DD:EE:FF";
    private static final String SPEAKER = "11:22:33:AA:BB:CC";

    @TempDir Path directory;
    private FakeBluezService service;
    private DbusBluezClient client;
    private FakeBluezService.FakeAdapter adapter;

    @BeforeEach
    void start() throws Exception {
        service = new FakeBluezService(directory);
        adapter = service.adapter("hci0", ADAPTER, "Living room", false);
        client = new DbusBluezClient(service.address(), Optional.of(service.socket()), Duration.ofSeconds(2));
    }

    @AfterEach
    void stop() throws Exception {
        if (client != null) client.close();
        if (service != null) service.close();
    }

    @Test
    void listsAdaptersInIdOrderWithoutMistakingDevicesForAdapters() throws Exception {
        service.adapter("hci2", "00:00:00:00:00:02", "Kitchen", true);
        service.adapter("hci1", "00:00:00:00:00:01", "Bedroom", false);
        service.device(adapter, SPEAKER);

        assertThat(client.adapters()).containsExactly(
                new BluetoothAdapterInfo("hci0", ADAPTER, "Living room", false),
                new BluetoothAdapterInfo("hci1", "00:00:00:00:00:01", "Bedroom", false),
                new BluetoothAdapterInfo("hci2", "00:00:00:00:00:02", "Kitchen", true));
    }

    @Test
    void powerOnChangesTheSelectedAdapterThroughDbusProperties() throws Exception {
        service.adapter("hci1", "00:00:00:00:00:01", "Bedroom", false);

        client.powerOn(ADAPTER.toLowerCase());

        assertThat(client.adapters()).extracting(BluetoothAdapterInfo::powered).containsExactly(true, false);
    }

    @Test
    void deviceReportsPreserveSpeakerStateAndExcludeOtherAdapters() throws Exception {
        var speaker = service.device(adapter, SPEAKER);
        speaker.property("Name", "  Speaker  ");
        speaker.property("Icon", "audio-card");
        speaker.property("Paired", true);
        speaker.property("Trusted", true);
        speaker.property("Connected", true);
        speaker.property("UUIDs", new String[] {BluetoothDeviceInfo.A2DP_SINK});
        speaker.property("RSSI", (short) -47);
        var other = service.adapter("hci1", "00:00:00:00:00:01", "Bedroom", true);
        service.device(other, "00:00:00:00:00:02");

        assertThat(client.devices(ADAPTER.toLowerCase())).containsExactly(new BluetoothDeviceInfo(
                SPEAKER, "  Speaker  ", "audio-card", true, true, true, List.of(BluetoothDeviceInfo.A2DP_SINK), (short) -47));
        assertThat(client.device(ADAPTER, SPEAKER.toLowerCase())).get()
                .extracting(BluetoothDeviceInfo::displayName).isEqualTo("Speaker");
        assertThat(client.device(ADAPTER, "00:00:00:00:00:99")).isEmpty();
    }

    @Test
    void missingDevicePropertiesUseAliasWithoutPresentingAnAddressAsAName() throws Exception {
        var speaker = service.device(adapter, SPEAKER);
        speaker.property("Name", " ");
        speaker.property("Alias", "Portable speaker");

        assertThat(client.device(ADAPTER, SPEAKER)).contains(new BluetoothDeviceInfo(
                SPEAKER, "Portable speaker", null, false, false, false, List.of(), null));

        speaker.property("Alias", SPEAKER.toLowerCase().replace(':', '-'));
        assertThat(client.device(ADAPTER, SPEAKER)).get()
                .extracting(BluetoothDeviceInfo::displayName).isEqualTo(SPEAKER);
        speaker.omit("Alias");
        speaker.omit("Name");
        assertThat(client.device(ADAPTER, SPEAKER)).get()
                .extracting(BluetoothDeviceInfo::name).isNull();
    }

    @Test
    void pairingTrustAndConnectionChangesAreVisibleInSubsequentDeviceReports() throws Exception {
        service.device(adapter, SPEAKER);

        client.pair(ADAPTER, SPEAKER);
        client.trust(ADAPTER, SPEAKER);
        client.connect(ADAPTER, SPEAKER);

        assertThat(client.device(ADAPTER, SPEAKER)).get().satisfies(device -> {
            assertThat(device.paired()).isTrue();
            assertThat(device.trusted()).isTrue();
            assertThat(device.connected()).isTrue();
        });
        client.disconnect(ADAPTER, SPEAKER);
        assertThat(client.device(ADAPTER, SPEAKER)).get()
                .extracting(BluetoothDeviceInfo::connected).isEqualTo(false);
    }

    @Test
    void alreadyCompletedPairingAndConnectionOperationsAreSuccessful() throws Exception {
        service.device(adapter, SPEAKER);
        client.pair(ADAPTER, SPEAKER);
        client.connect(ADAPTER, SPEAKER);

        assertThatCode(() -> client.pair(ADAPTER, SPEAKER)).doesNotThrowAnyException();
        assertThatCode(() -> client.connect(ADAPTER, SPEAKER)).doesNotThrowAnyException();
        client.disconnect(ADAPTER, SPEAKER);
        assertThatCode(() -> client.disconnect(ADAPTER, SPEAKER)).doesNotThrowAnyException();
        assertThat(client.device(ADAPTER, SPEAKER)).get().satisfies(device -> {
            assertThat(device.paired()).isTrue();
            assertThat(device.connected()).isFalse();
        });
    }

    @Test
    void removingADeviceLeavesDevicesOnOtherAdaptersAlone() throws Exception {
        service.device(adapter, SPEAKER);
        var other = service.adapter("hci1", "00:00:00:00:00:01", "Bedroom", true);
        service.device(other, SPEAKER);

        client.remove(ADAPTER, SPEAKER);

        assertThat(client.devices(ADAPTER)).isEmpty();
        assertThat(client.device("00:00:00:00:00:01", SPEAKER)).isPresent();
        assertThatCode(() -> client.remove(ADAPTER, SPEAKER)).doesNotThrowAnyException();
    }

    @Test
    void discoveryStopsScanningAndReturnsTheDiscoveredDevices() throws Exception {
        service.device(adapter, SPEAKER);

        assertThat(client.discover(ADAPTER, Duration.ZERO)).extracting(BluetoothDeviceInfo::address).containsExactly(SPEAKER);
        assertThat(adapter.discovering()).isFalse();
        assertThat(adapter.stops()).isEqualTo(1);
    }

    @Test
    void anotherClientsScanAndAFailedStopStillAllowReadingDiscoveredDevices() throws Exception {
        service.device(adapter, SPEAKER);
        adapter.alreadyScanning();
        adapter.failStop();

        assertThat(client.discover(ADAPTER, Duration.ZERO)).extracting(BluetoothDeviceInfo::address).containsExactly(SPEAKER);
        assertThat(adapter.stops()).isEqualTo(1);
    }

    @Test
    void aPairingRefusalIsClassifiedAndDoesNotPoisonTheConnection() throws Exception {
        service.device(adapter, SPEAKER).rejectPairing();

        assertThatThrownBy(() -> client.pair(ADAPTER, SPEAKER)).isInstanceOf(BluezException.class)
                .satisfies(error -> assertThat(((BluezException) error).failure()).isEqualTo(BluezFailure.PAIRING_REJECTED))
                .hasMessageContaining("refused", SPEAKER);
        assertThat(client.adapters()).hasSize(1);
        assertThat(client.device(ADAPTER, SPEAKER)).get()
                .extracting(BluetoothDeviceInfo::paired).isEqualTo(false);
    }

    @Test
    void aConnectionRefusalIsClassifiedFromTheWireErrorName() throws Exception {
        service.device(adapter, SPEAKER).refuseConnection();

        assertThatThrownBy(() -> client.connect(ADAPTER, SPEAKER)).isInstanceOf(BluezException.class)
                .satisfies(error -> assertThat(((BluezException) error).failure()).isEqualTo(BluezFailure.UNREACHABLE))
                .hasMessageContaining("did not answer", SPEAKER);
        assertThat(client.device(ADAPTER, SPEAKER)).get()
                .extracting(BluetoothDeviceInfo::connected).isEqualTo(false);
    }

    @Test
    void anUnansweredPairingCallTimesOutWithoutPoisoningTheConnection() throws Exception {
        var speaker = service.device(adapter, SPEAKER);
        speaker.holdPairing();
        try {
            assertThatThrownBy(() -> client.pair(ADAPTER, SPEAKER)).isInstanceOf(BluezException.class)
                    .satisfies(error -> assertThat(((BluezException) error).failure()).isEqualTo(BluezFailure.TIMEOUT))
                    .hasMessageContaining("did not answer in time", "Pair");
            assertThat(client.adapters()).hasSize(1);
        } finally {
            speaker.releasePairing();
        }
    }

    @Test
    void missingAdaptersAndDevicesProduceActionableFailures() {
        assertThatThrownBy(() -> client.powerOn("00:00:00:00:00:99")).isInstanceOf(BluezException.class)
                .satisfies(error -> assertThat(((BluezException) error).failure()).isEqualTo(BluezFailure.NO_ADAPTER));
        assertThatThrownBy(() -> client.pair(ADAPTER, SPEAKER)).isInstanceOf(BluezException.class)
                .satisfies(error -> assertThat(((BluezException) error).failure()).isEqualTo(BluezFailure.NOT_FOUND));
        assertThatThrownBy(() -> client.trust(ADAPTER, SPEAKER)).isInstanceOf(BluezException.class)
                .satisfies(error -> assertThat(((BluezException) error).failure()).isEqualTo(BluezFailure.NOT_FOUND));
    }

    @Test
    void closingAConnectionAllowsTheNextCallToOpenAFreshOne() throws Exception {
        assertThat(client.adapters()).hasSize(1);
        client.close();
        adapter.property("Alias", "Renamed adapter");

        assertThat(client.adapters()).extracting(BluetoothAdapterInfo::alias).containsExactly("Renamed adapter");
    }
}

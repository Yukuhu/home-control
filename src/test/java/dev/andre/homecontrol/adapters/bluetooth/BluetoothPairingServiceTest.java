package dev.andre.homecontrol.adapters.bluetooth;

import dev.andre.homecontrol.adapters.bluetooth.bluez.BluetoothDeviceInfo;
import dev.andre.homecontrol.adapters.bluetooth.bluez.FakeBluezClient;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.core.DeviceNotFoundException;
import dev.andre.homecontrol.device.DeviceManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static dev.andre.homecontrol.adapters.bluetooth.bluez.BluetoothDeviceInfo.A2DP_SINK;
import static dev.andre.homecontrol.adapters.bluetooth.bluez.BluezFailure.ACCESS_DENIED;
import static dev.andre.homecontrol.adapters.bluetooth.bluez.BluezFailure.BUSY;
import static dev.andre.homecontrol.adapters.bluetooth.bluez.BluezFailure.NO_AUDIO_PROFILE;
import static dev.andre.homecontrol.adapters.bluetooth.bluez.BluezFailure.PAIRING_REJECTED;
import static dev.andre.homecontrol.adapters.bluetooth.bluez.BluezFailure.UNREACHABLE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BluetoothPairingServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-16T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final FakeBluezClient bluez = new FakeBluezClient();
    private final DeviceManager devices = mock(DeviceManager.class);
    private final BluetoothProperties properties = BluetoothProperties.defaults();
    private final BluetoothPairingService service = new BluetoothPairingService(bluez, devices, properties, CLOCK);

    @BeforeEach
    void setUp() {
        when(devices.device(anyString())).thenReturn(Optional.empty());
    }

    @Test
    void scanPowersTheAdapterAndListsSpeakersFirst() {
        bluez.adapterPowered(false);
        bluez.addDevice("11:11:11:11:11:01", "Phone").icon("phone")
                .uuids("0000110a-0000-1000-8000-00805f9b34fb").rssi(-40);
        bluez.addDevice("11:11:11:11:11:02", "Headphones").icon("audio-headphones").rssi(-80);
        bluez.addDevice("11:11:11:11:11:03", "JBL Flip 5").icon("audio-card").uuids(A2DP_SINK).rssi(-70);
        bluez.addDevice("11:11:11:11:11:04", null).rssi(-50);

        BluetoothScan scan = service.scan();

        assertThat(bluez.calls()).containsExactly("powerOn " + FakeBluezClient.ADAPTER,
                "discover " + FakeBluezClient.ADAPTER + " 10s");
        assertThat(scan.speakers()).extracting(BluetoothDeviceInfo::address)
                .containsExactly("11:11:11:11:11:03", "11:11:11:11:11:04", "11:11:11:11:11:02");
        assertThat(scan.hiddenCount()).isEqualTo(1);
        assertThat(scan.error()).isNull();
        assertThat(scan.scannedAt()).isEqualTo(NOW);
        assertThat(service.lastScan()).isSameAs(scan);
    }

    @Test
    void scanFailureIsAMessage() {
        bluez.noAdapters();
        BluetoothScan scan = service.scan();
        assertThat(scan.error()).contains("No Bluetooth adapter");
        assertThat(scan.speakers()).isEmpty();
    }

    @Test
    void aConfiguredAdapterIsUsed() {
        bluez.addAdapter("hci1", "00:1A:7D:DA:71:99", true);
        BluetoothPairingService withAdapter = new BluetoothPairingService(bluez, devices,
                properties.withAdapter("hci1"), CLOCK);
        withAdapter.scan();
        assertThat(bluez.calls()).contains("discover 00:1A:7D:DA:71:99 10s");

        BluetoothPairingService missing = new BluetoothPairingService(bluez, devices,
                properties.withAdapter("hci7"), CLOCK);
        BluetoothScan scan = missing.scan();
        assertThat(scan.error()).contains("Adapter hci7 not found").contains("hci0 (00:1A:7D:DA:71:13)");
    }

    @Test
    void pairsTrustsConnectsAndRegisters() throws Exception {
        bluez.known("AA:BB:CC:DD:EE:FF", "JBL Flip 5").uuids(A2DP_SINK);

        BluetoothPairing result = service.pair("aa:bb:cc:dd:ee:ff");

        assertThat(bluez.calls()).containsExactly("pair AA:BB:CC:DD:EE:FF", "trust AA:BB:CC:DD:EE:FF", "connect AA:BB:CC:DD:EE:FF");
        assertThat(result.warning()).isNull();
        assertThat(result.device().id()).isEqualTo("bluetooth-aa-bb-cc-dd-ee-ff");
        assertThat(result.device().name()).isEqualTo("JBL Flip 5");
        assertThat(result.device().kind()).isEqualTo(DeviceKind.BLUETOOTH);
        assertThat(result.device().host()).isEqualTo("AA:BB:CC:DD:EE:FF");
        // Device's compact constructor copies settings through Map.copyOf, which does not preserve order.
        assertThat(result.device().adapterSettings("bluetooth"))
                .containsExactlyInAnyOrderEntriesOf(Map.of("address", "AA:BB:CC:DD:EE:FF", "adapter", FakeBluezClient.ADAPTER));
        assertThat(result.device().lastSeen()).isEqualTo(NOW);

        ArgumentCaptor<Device> captor = ArgumentCaptor.forClass(Device.class);
        verify(devices).adopt(captor.capture());
        assertThat(captor.getValue()).isEqualTo(result.device());
    }

    @Test
    void anAlreadyPairedSpeakerIsOnlyTrustedAndConnected() throws Exception {
        bluez.known("AA:BB:CC:DD:EE:FF", "JBL Flip 5").uuids(A2DP_SINK).paired(true);
        service.pair("AA:BB:CC:DD:EE:FF");
        assertThat(bluez.calls()).containsExactly("trust AA:BB:CC:DD:EE:FF", "connect AA:BB:CC:DD:EE:FF");
    }

    @Test
    void aConnectedSpeakerIsNotConnectedAgain() throws Exception {
        bluez.known("AA:BB:CC:DD:EE:FF", "JBL Flip 5").uuids(A2DP_SINK).paired(true).connected(true);
        service.pair("AA:BB:CC:DD:EE:FF");
        assertThat(bluez.calls()).containsExactly("trust AA:BB:CC:DD:EE:FF");
    }

    @Test
    void refusesANonAudioDeviceBeforePairing() {
        bluez.known("AA:BB:CC:DD:EE:FF", "Phone").uuids("0000110a-0000-1000-8000-00805f9b34fb");
        assertThatThrownBy(() -> service.pair("AA:BB:CC:DD:EE:FF"))
                .isInstanceOf(BluetoothSetupException.class)
                .hasMessage("Phone is not a speaker or headphones (no A2DP audio sink)");
        assertThat(bluez.calls()).isEmpty();
        verify(devices, never()).adopt(any());
    }

    @Test
    void removesADeviceThatTurnsOutNotToBeASpeaker() {
        bluez.known("AA:BB:CC:DD:EE:FF", "JBL Flip 5").uuidsAfterPairing("00001101-0000-1000-8000-00805f9b34fb");
        assertThatThrownBy(() -> service.pair("AA:BB:CC:DD:EE:FF"))
                .isInstanceOf(BluetoothSetupException.class)
                .hasMessage("JBL Flip 5 is not a speaker or headphones (no A2DP audio sink)");
        assertThat(bluez.calls()).endsWith("remove AA:BB:CC:DD:EE:FF");
        verify(devices, never()).adopt(any());
    }

    @Test
    void acceptsASpeakerWhoseServicesStayUnknown() throws Exception {
        bluez.known("AA:BB:CC:DD:EE:FF", "Mystery Speaker");
        BluetoothPairing result = service.pair("AA:BB:CC:DD:EE:FF");
        verify(devices).adopt(any());
        assertThat(result.warning()).isNull();
    }

    @Test
    void aRejectedPairingIsExplained() {
        bluez.known("AA:BB:CC:DD:EE:FF", "JBL Flip 5").uuids(A2DP_SINK);
        bluez.failNext("pair", PAIRING_REJECTED, "Authentication Rejected");
        assertThatThrownBy(() -> service.pair("AA:BB:CC:DD:EE:FF"))
                .isInstanceOf(BluetoothSetupException.class).hasMessageContaining("refused pairing");
        verify(devices, never()).adopt(any());
    }

    @Test
    void anUnknownAddressIsExplained() {
        assertThatThrownBy(() -> service.pair("AA:BB:CC:DD:EE:00"))
                .isInstanceOf(BluetoothSetupException.class).hasMessageContaining("pairing mode and scan again");
    }

    @Test
    void registersEvenWhenConnectFails() throws Exception {
        bluez.known("AA:BB:CC:DD:EE:FF", "JBL Flip 5").uuids(A2DP_SINK);
        bluez.failNext("connect", UNREACHABLE, "br-connection-page-timeout");
        BluetoothPairing result = service.pair("AA:BB:CC:DD:EE:FF");
        verify(devices).adopt(any());
        assertThat(result.warning()).startsWith("Paired JBL Flip 5, but it did not connect: The speaker did not answer");
    }

    @Test
    void pairingAgainKeepsNameAndAudioDevice() throws Exception {
        bluez.known("AA:BB:CC:DD:EE:FF", "JBL Flip 5").uuids(A2DP_SINK);
        Device existing = new Device("bluetooth-aa-bb-cc-dd-ee-ff", "Kitchen speaker", DeviceKind.BLUETOOTH,
                "AA:BB:CC:DD:EE:FF", Map.of("bluetooth", new BluetoothSettings("AA:BB:CC:DD:EE:FF",
                FakeBluezClient.ADAPTER, "alsa/bluealsa:DEV=AA:BB:CC:DD:EE:FF,PROFILE=a2dp").toMap()), NOW);
        when(devices.device("bluetooth-aa-bb-cc-dd-ee-ff")).thenReturn(Optional.of(existing));

        BluetoothPairing result = service.pair("AA:BB:CC:DD:EE:FF");

        assertThat(result.device().name()).isEqualTo("Kitchen speaker");
        assertThat(result.device().adapterSettings("bluetooth"))
                .containsEntry("audioDevice", "alsa/bluealsa:DEV=AA:BB:CC:DD:EE:FF,PROFILE=a2dp");
    }

    @Test
    void rejectsABadAddress() {
        assertThatThrownBy(() -> service.pair("nope"))
                .isInstanceOf(BluetoothSetupException.class).hasMessageContaining("Not a MAC address");
    }

    @Test
    void connectsAndDisconnectsARegisteredSpeaker() throws Exception {
        Device registered = registeredSpeaker();
        when(devices.device("bluetooth-aa-bb-cc-dd-ee-ff")).thenReturn(Optional.of(registered));
        bluez.known("AA:BB:CC:DD:EE:FF", "JBL Flip 5");

        Device connected = service.connect("bluetooth-aa-bb-cc-dd-ee-ff");
        assertThat(bluez.calls()).contains("connect AA:BB:CC:DD:EE:FF");
        assertThat(connected).isEqualTo(registered);

        Device disconnected = service.disconnect("bluetooth-aa-bb-cc-dd-ee-ff");
        assertThat(bluez.calls()).contains("disconnect AA:BB:CC:DD:EE:FF");
        assertThat(disconnected).isEqualTo(registered);
    }

    @Test
    void aBluezFailureDuringConnectIsExplained() {
        Device registered = registeredSpeaker();
        when(devices.device("bluetooth-aa-bb-cc-dd-ee-ff")).thenReturn(Optional.of(registered));
        // No matching device known to the fake -> NOT_FOUND from requireVisibleDevice.
        assertThatThrownBy(() -> service.connect("bluetooth-aa-bb-cc-dd-ee-ff")).isInstanceOf(BluetoothSetupException.class);
    }

    @Test
    void unknownSpeakersAreNotFound() {
        assertThatThrownBy(() -> service.connect("ghost")).isInstanceOf(DeviceNotFoundException.class);
        assertThatThrownBy(() -> service.setAudioDevice("ghost", "")).isInstanceOf(DeviceNotFoundException.class);

        Device notBluetooth = new Device("androidtv-1", "Shield", DeviceKind.ANDROID_TV, "192.168.1.2", Map.of(), NOW);
        when(devices.device("androidtv-1")).thenReturn(Optional.of(notBluetooth));
        assertThatThrownBy(() -> service.connect("androidtv-1")).isInstanceOf(DeviceNotFoundException.class);
    }

    @Test
    void setsAndClearsTheAudioDevice() throws Exception {
        Device registered = registeredSpeaker();
        when(devices.device("bluetooth-aa-bb-cc-dd-ee-ff")).thenReturn(Optional.of(registered));

        ArgumentCaptor<Device> captor = ArgumentCaptor.forClass(Device.class);
        service.setAudioDevice("bluetooth-aa-bb-cc-dd-ee-ff", "pulse/bluez_output.AA_BB_CC_DD_EE_FF.1");
        verify(devices).adopt(captor.capture());
        assertThat(captor.getValue().adapterSettings("bluetooth"))
                .containsEntry("audioDevice", "pulse/bluez_output.AA_BB_CC_DD_EE_FF.1");

        Mockito.reset(devices);
        when(devices.device("bluetooth-aa-bb-cc-dd-ee-ff")).thenReturn(Optional.of(registered));
        service.setAudioDevice("bluetooth-aa-bb-cc-dd-ee-ff", "");
        verify(devices).adopt(captor.capture());
        assertThat(captor.getValue().adapterSettings("bluetooth")).doesNotContainKey("audioDevice");

        assertThatThrownBy(() -> service.setAudioDevice("bluetooth-aa-bb-cc-dd-ee-ff", "bad device"))
                .isInstanceOf(BluetoothSetupException.class).hasMessageContaining("audio device");
    }

    @Test
    void aBusyAdapterIsExplained() {
        bluez.failNext("discover", BUSY, "Operation already in progress");
        BluetoothScan scan = service.scan();
        assertThat(scan.error()).containsIgnoringCase("busy");
    }

    @Test
    void aMissingAudioServiceIsExplainedWhenConnecting() throws Exception {
        bluez.known("AA:BB:CC:DD:EE:FF", "JBL Flip 5").uuids(A2DP_SINK);
        bluez.failNext("connect", NO_AUDIO_PROFILE, "br-connection-profile-unavailable");

        BluetoothPairing result = service.pair("AA:BB:CC:DD:EE:FF");

        verify(devices).adopt(any());
        assertThat(result.warning()).contains("no Bluetooth audio service").contains("PipeWire");
    }

    @Test
    void accessDeniedIsExplained() {
        bluez.known("AA:BB:CC:DD:EE:FF", "JBL Flip 5").uuids(A2DP_SINK);
        bluez.unavailable(ACCESS_DENIED);

        assertThatThrownBy(() -> service.pair("AA:BB:CC:DD:EE:FF"))
                .isInstanceOf(BluetoothSetupException.class).hasMessageContaining("refused this container");
    }

    private Device registeredSpeaker() {
        return new Device("bluetooth-aa-bb-cc-dd-ee-ff", "JBL Flip 5", DeviceKind.BLUETOOTH, "AA:BB:CC:DD:EE:FF",
                Map.of("bluetooth", new BluetoothSettings("AA:BB:CC:DD:EE:FF", FakeBluezClient.ADAPTER, "").toMap()), NOW);
    }
}

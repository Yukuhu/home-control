package dev.andre.homecontrol.web;

import dev.andre.homecontrol.adapters.androidtv.PairingService;
import dev.andre.homecontrol.adapters.bluetooth.BluetoothHostChecks;
import dev.andre.homecontrol.adapters.bluetooth.BluetoothPairing;
import dev.andre.homecontrol.adapters.bluetooth.BluetoothPairingService;
import dev.andre.homecontrol.adapters.bluetooth.BluetoothProperties;
import dev.andre.homecontrol.adapters.bluetooth.BluetoothScan;
import dev.andre.homecontrol.adapters.bluetooth.BluetoothSetupAdvice;
import dev.andre.homecontrol.adapters.bluetooth.BluetoothSetupController;
import dev.andre.homecontrol.adapters.bluetooth.BluetoothSetupException;
import dev.andre.homecontrol.adapters.bluetooth.BluetoothSettings;
import dev.andre.homecontrol.adapters.bluetooth.HostCheck;
import dev.andre.homecontrol.adapters.bluetooth.bluez.BluetoothDeviceInfo;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.core.DeviceNotFoundException;
import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.DeviceStatus;
import dev.andre.homecontrol.device.DeviceManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static dev.andre.homecontrol.adapters.bluetooth.bluez.BluetoothDeviceInfo.A2DP_SINK;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {SetupController.class, BluetoothSetupController.class},
        properties = "home-control.bluetooth.enabled=true")
@Import(BluetoothSetupAdvice.class)
class BluetoothSetupControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    PairingService androidPairing;

    @MockitoBean
    DeviceManager devices;

    @MockitoBean
    BluetoothPairingService pairing;

    @MockitoBean
    BluetoothHostChecks checks;

    @MockitoBean
    BluetoothProperties properties;

    @BeforeEach
    void defaults() {
        given(devices.devices()).willReturn(List.of());
        given(devices.pairable()).willReturn(List.of());
        given(devices.addable()).willReturn(List.of());
        given(properties.scanSeconds()).willReturn(10);
        given(checks.results()).willReturn(List.of(
                new HostCheck("dbus-socket", "D-Bus system socket", true, "Found /run/dbus/system_bus_socket"),
                new HostCheck("bluez", "BlueZ", true, "BlueZ answered on the system bus"),
                new HostCheck("adapter", "Bluetooth adapter", true, "hci0 (00:1A:7D:DA:71:13)")));
        given(pairing.lastScan()).willReturn(BluetoothScan.NONE);
    }

    @Test
    void showsTheSectionWithHostChecks() throws Exception {
        given(checks.results()).willReturn(List.of(
                new HostCheck("dbus-socket", "D-Bus system socket", true, "Found /run/dbus/system_bus_socket"),
                new HostCheck("bluez", "BlueZ", false,
                        "BlueZ is not running on the host. Install bluez and run: sudo systemctl enable --now bluetooth")));

        mockMvc.perform(get("/setup")).andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"bluetooth\"")))
                .andExpect(content().string(containsString("Bluetooth speakers")))
                .andExpect(content().string(containsString("D-Bus system socket")))
                .andExpect(content().string(containsString("sudo systemctl enable --now bluetooth")))
                .andExpect(content().string(containsString("data-check=\"bluez\"")))
                .andExpect(content().string(containsString("action=\"/setup/bluetooth/scan\"")))
                .andExpect(content().string(containsString("Scanning takes about 10 seconds")))
                .andExpect(content().string(containsString("docs/bluetooth-speakers.md")));
    }

    @Test
    void showsScanResults() throws Exception {
        given(pairing.lastScan()).willReturn(new BluetoothScan(Instant.now(), List.of(
                new BluetoothDeviceInfo("AA:BB:CC:DD:EE:FF", "JBL Flip 5", "audio-card", false, false, false,
                        List.of(A2DP_SINK), (short) -60),
                new BluetoothDeviceInfo("11:22:33:44:55:66", null, null, true, false, false, List.of(), null)), 2, null));

        mockMvc.perform(get("/setup")).andExpect(status().isOk())
                .andExpect(content().string(containsString("JBL Flip 5")))
                .andExpect(content().string(containsString("speaker")))
                .andExpect(content().string(containsString("11:22:33:44:55:66")))
                .andExpect(content().string(containsString("unknown type")))
                .andExpect(content().string(containsString("paired with the host")))
                .andExpect(content().string(containsString("name=\"address\" value=\"AA:BB:CC:DD:EE:FF\"")))
                .andExpect(content().string(containsString("Pair and add")))
                .andExpect(content().string(containsString("2 other Bluetooth devices hidden")));
    }

    @Test
    void listsRegisteredSpeakers() throws Exception {
        Device speaker = new Device("bluetooth-aa-bb-cc-dd-ee-ff", "JBL Flip 5", DeviceKind.BLUETOOTH, "AA:BB:CC:DD:EE:FF",
                Map.of("bluetooth", new BluetoothSettings("AA:BB:CC:DD:EE:FF", "00:1A:7D:DA:71:13",
                        "pulse/bluez_output.AA_BB_CC_DD_EE_FF.1").toMap()), Instant.now());
        Device tv = new Device("androidtv-1", "Shield", DeviceKind.ANDROID_TV, "192.168.1.2", Map.of(), Instant.now());
        given(devices.devices()).willReturn(List.of(speaker, tv));
        given(devices.state("bluetooth-aa-bb-cc-dd-ee-ff")).willReturn(DeviceState.initial().withStatus(DeviceStatus.CONNECTED));
        given(pairing.lastScan()).willReturn(new BluetoothScan(Instant.now(),
                List.of(new BluetoothDeviceInfo("AA:BB:CC:DD:EE:FF", "JBL Flip 5", "audio-card", true, true, true,
                        List.of(A2DP_SINK), null)), 0, null));

        mockMvc.perform(get("/setup")).andExpect(status().isOk())
                .andExpect(content().string(containsString("/setup/bluetooth/connect")))
                .andExpect(content().string(containsString("/setup/bluetooth/disconnect")))
                .andExpect(content().string(containsString("/setup/bluetooth/audio-device")))
                .andExpect(content().string(containsString("value=\"pulse/bluez_output.AA_BB_CC_DD_EE_FF.1\"")))
                .andExpect(content().string(containsString("CONNECTED")))
                .andExpect(content().string(containsString("Added")));
    }

    @Test
    void checkAgainInvalidates() throws Exception {
        mockMvc.perform(post("/setup/bluetooth/check"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/setup#bluetooth"));
        verify(checks).invalidate();
    }

    @Test
    void scanRedirectsWithAMessage() throws Exception {
        given(pairing.scan()).willReturn(new BluetoothScan(Instant.now(),
                List.of(new BluetoothDeviceInfo("AA:BB:CC:DD:EE:FF", "JBL Flip 5", "audio-card", false, false, false,
                        List.of(A2DP_SINK), null)), 0, null));
        mockMvc.perform(post("/setup/bluetooth/scan"))
                .andExpect(redirectedUrl("/setup#bluetooth"))
                .andExpect(flash().attribute("bluetoothMessage", "Found 1 device"));

        given(pairing.scan()).willReturn(BluetoothScan.NONE);
        mockMvc.perform(post("/setup/bluetooth/scan"))
                .andExpect(flash().attribute("bluetoothMessage",
                        "No speakers found. Put the speaker into pairing mode and scan again."));

        given(pairing.scan()).willReturn(new BluetoothScan(Instant.now(), List.of(), 0, "No Bluetooth adapter found on the host."));
        mockMvc.perform(post("/setup/bluetooth/scan"))
                .andExpect(flash().attribute("bluetoothError", "No Bluetooth adapter found on the host."));
        verify(checks, org.mockito.Mockito.atLeastOnce()).invalidate();
    }

    @Test
    void pairingOpensTheNewSpeaker() throws Exception {
        Device device = new Device("bluetooth-aa-bb-cc-dd-ee-ff", "JBL Flip 5", DeviceKind.BLUETOOTH, "AA:BB:CC:DD:EE:FF",
                Map.of("bluetooth", new BluetoothSettings("AA:BB:CC:DD:EE:FF", "00:1A:7D:DA:71:13", "").toMap()), Instant.now());
        given(pairing.pair("AA:BB:CC:DD:EE:FF")).willReturn(new BluetoothPairing(device, null));

        mockMvc.perform(post("/setup/bluetooth/pair").param("address", "AA:BB:CC:DD:EE:FF"))
                .andExpect(redirectedUrl("/?device=bluetooth-aa-bb-cc-dd-ee-ff"));
    }

    @Test
    void aPairingWarningStaysOnSetup() throws Exception {
        Device device = new Device("bluetooth-aa-bb-cc-dd-ee-ff", "JBL Flip 5", DeviceKind.BLUETOOTH, "AA:BB:CC:DD:EE:FF",
                Map.of("bluetooth", new BluetoothSettings("AA:BB:CC:DD:EE:FF", "00:1A:7D:DA:71:13", "").toMap()), Instant.now());
        String warning = "Paired JBL Flip 5, but it did not connect: The speaker did not answer";
        given(pairing.pair("AA:BB:CC:DD:EE:FF")).willReturn(new BluetoothPairing(device, warning));

        mockMvc.perform(post("/setup/bluetooth/pair").param("address", "AA:BB:CC:DD:EE:FF"))
                .andExpect(redirectedUrl("/setup#bluetooth"))
                .andExpect(flash().attribute("bluetoothError", warning));
    }

    @Test
    void aPairingFailureIsShown() throws Exception {
        String message = "The speaker refused pairing (x). Put it into pairing mode and try again.";
        willThrow(new BluetoothSetupException(message)).given(pairing).pair("AA:BB:CC:DD:EE:FF");

        mockMvc.perform(post("/setup/bluetooth/pair").param("address", "AA:BB:CC:DD:EE:FF"))
                .andExpect(redirectedUrl("/setup#bluetooth"))
                .andExpect(flash().attribute("bluetoothError", message));
    }

    @Test
    void connectDisconnectAndAudioDevice() throws Exception {
        Device device = new Device("bluetooth-aa-bb-cc-dd-ee-ff", "JBL Flip 5", DeviceKind.BLUETOOTH, "AA:BB:CC:DD:EE:FF",
                Map.of("bluetooth", new BluetoothSettings("AA:BB:CC:DD:EE:FF", "00:1A:7D:DA:71:13", "").toMap()), Instant.now());
        given(pairing.connect("bluetooth-aa-bb-cc-dd-ee-ff")).willReturn(device);
        mockMvc.perform(post("/setup/bluetooth/connect").param("id", "bluetooth-aa-bb-cc-dd-ee-ff"))
                .andExpect(flash().attribute("bluetoothMessage", "Connected JBL Flip 5"));

        given(pairing.disconnect("bluetooth-aa-bb-cc-dd-ee-ff")).willReturn(device);
        mockMvc.perform(post("/setup/bluetooth/disconnect").param("id", "bluetooth-aa-bb-cc-dd-ee-ff"))
                .andExpect(flash().attribute("bluetoothMessage", "Disconnected JBL Flip 5"));

        given(pairing.setAudioDevice("bluetooth-aa-bb-cc-dd-ee-ff", "pulse/x")).willReturn(device);
        mockMvc.perform(post("/setup/bluetooth/audio-device").param("id", "bluetooth-aa-bb-cc-dd-ee-ff")
                        .param("audioDevice", "pulse/x"))
                .andExpect(flash().attribute("bluetoothMessage", "JBL Flip 5 plays on pulse/x"));

        given(pairing.setAudioDevice("bluetooth-aa-bb-cc-dd-ee-ff", "")).willReturn(device);
        mockMvc.perform(post("/setup/bluetooth/audio-device").param("id", "bluetooth-aa-bb-cc-dd-ee-ff")
                        .param("audioDevice", ""))
                .andExpect(flash().attribute("bluetoothMessage", "JBL Flip 5 finds its audio output automatically"));

        willThrow(new BluetoothSetupException("bad")).given(pairing).connect("ghost-but-registered");
        mockMvc.perform(post("/setup/bluetooth/connect").param("id", "ghost-but-registered"))
                .andExpect(flash().attribute("bluetoothError", "bad"));
    }

    @Test
    void unknownSpeakersAre404() throws Exception {
        willThrow(new DeviceNotFoundException("No Bluetooth speaker ghost")).given(pairing).connect("ghost");
        mockMvc.perform(post("/setup/bluetooth/connect").param("id", "ghost"))
                .andExpect(status().isNotFound())
                .andExpect(content().string("No Bluetooth speaker ghost"));
    }
}

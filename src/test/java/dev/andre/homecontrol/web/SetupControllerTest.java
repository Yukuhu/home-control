package dev.andre.homecontrol.web;

import dev.andre.homecontrol.adapters.androidtv.AndroidTvSettings;
import dev.andre.homecontrol.adapters.androidtv.PairingService;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.core.DiscoveredDevice;
import dev.andre.homecontrol.device.DeviceManager;
import dev.andre.homecontrol.storage.StorageException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.AccessDeniedException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(SetupController.class)
class SetupControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    PairingService pairing;

    @MockitoBean
    DeviceManager devices;

    @BeforeEach
    void noDevicesUnlessATestSaysOtherwise() {
        given(devices.devices()).willReturn(List.of());
        given(devices.pairable()).willReturn(List.of());
        given(devices.addable()).willReturn(List.of());
    }

    @Test
    void showsAnActionableStorageErrorBeforePairing() throws Exception {
        willThrow(new StorageException(
                "Shield data directory is not writable: /data; check that /data is bind-mounted and writable",
                new AccessDeniedException("/data")))
                .given(pairing).begin("192.168.1.50", null);

        mockMvc.perform(post("/setup/pair").param("host", "192.168.1.50"))
                .andExpect(status().isOk())
                .andExpect(view().name("setup"))
                .andExpect(content().string(containsString("/data")))
                .andExpect(content().string(containsString("bind-mounted and writable")));
    }

    @Test
    void rendersTheSetupPageWithEveryPairedDeviceDiscoveredDevicesAndManualEntry() throws Exception {
        given(devices.pairable()).willReturn(List.of(
                new DiscoveredDevice("androidtv", "Living Room Shield", "192.168.1.50", 6466)));
        given(devices.devices()).willReturn(List.of(
                AndroidTvSettings.device("bedroom", "Bedroom Shield", "192.168.1.51", 6466, null, Instant.now()),
                AndroidTvSettings.device("living-room", "Kitchen TV", "192.168.1.52", 6466, null, Instant.now())));
        given(pairing.inProgress()).willReturn(false);

        String html = mockMvc.perform(get("/setup"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Living Room Shield")))
                .andExpect(content().string(containsString("Bedroom Shield")))
                .andExpect(content().string(containsString("Kitchen TV")))
                .andExpect(content().string(containsString("removes the stored pairing credential")))
                .andExpect(content().string(containsString("pair again")))
                .andExpect(content().string(containsString("name=\"host\"")))
                .andReturn().getResponse().getContentAsString();

        Matcher forgetIds = Pattern.compile("name=\"id\"").matcher(html);
        assertThat(forgetIds.results().count()).isEqualTo(2);
    }

    @Test
    void offersDiscoveredCastReceiversWithAnAddButton() throws Exception {
        given(devices.addable()).willReturn(List.of(
                new DiscoveredDevice("cast", "Kitchen speaker", "10.0.0.9", 8009, Map.of())));

        mockMvc.perform(get("/setup"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Kitchen speaker")))
                .andExpect(content().string(containsString("action=\"/setup/add\"")))
                .andExpect(content().string(containsString("value=\"8009\"")));
    }

    @Test
    void addsADiscoveredReceiver() throws Exception {
        mockMvc.perform(post("/setup/add").param("adapter", "cast").param("host", "10.0.0.9").param("port", "8009"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/setup"));

        verify(devices).addDiscovered("cast", "10.0.0.9", 8009);
    }

    @Test
    void mergesAndSplitsDevices() throws Exception {
        mockMvc.perform(post("/setup/merge").param("target", "10-0-0-5").param("source", "cast-10-0-0-5"))
                .andExpect(redirectedUrl("/setup"));
        mockMvc.perform(post("/setup/split").param("id", "10-0-0-5").param("adapter", "cast"))
                .andExpect(redirectedUrl("/setup"));

        verify(devices).merge("10-0-0-5", "cast-10-0-0-5");
        verify(devices).split("10-0-0-5", "cast");
    }

    @Test
    void showsWhyAMergeWasRefused() throws Exception {
        given(devices.merge("a", "b")).willThrow(new IllegalArgumentException("Merge the other way round: nope"));

        mockMvc.perform(post("/setup/merge").param("target", "a").param("source", "b"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Merge the other way round: nope")));
    }

    @Test
    void offersSplitOnlyForDevicesWithSeveralConnectionsAndMergeOnlyWithTwoDevices() throws Exception {
        Map<String, Map<String, String>> adapters = new LinkedHashMap<>();
        adapters.put("androidtv", Map.of());
        adapters.put("cast", Map.of());
        Device shield = new Device("10-0-0-5", "Living Room TV", DeviceKind.ANDROID_TV, "10.0.0.5",
                adapters, Instant.EPOCH);
        Device kitchen = new Device("cast-10-0-0-9", "Kitchen", DeviceKind.CAST, "10.0.0.9",
                Map.of("cast", Map.of()), Instant.EPOCH);
        given(devices.devices()).willReturn(List.of(kitchen, shield));

        mockMvc.perform(get("/setup"))
                .andExpect(content().string(containsString("action=\"/setup/split\"")))
                .andExpect(content().string(containsString("name=\"adapter\" value=\"cast\"")))
                .andExpect(content().string(not(containsString("name=\"adapter\" value=\"androidtv\""))))
                .andExpect(content().string(containsString("action=\"/setup/merge\"")));
    }
}

package dev.andre.homecontrol.web;

import dev.andre.homecontrol.adapters.androidtv.AndroidTvSettings;
import dev.andre.homecontrol.adapters.androidtv.PairingService;
import dev.andre.homecontrol.core.DiscoveredDevice;
import dev.andre.homecontrol.device.DeviceManager;
import dev.andre.homecontrol.storage.StorageException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.AccessDeniedException;
import java.time.Instant;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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

    @Test
    void showsAnActionableStorageErrorBeforePairing() throws Exception {
        given(devices.discovered()).willReturn(List.of());
        given(devices.devices()).willReturn(List.of());
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
        given(devices.discovered()).willReturn(List.of(
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
}

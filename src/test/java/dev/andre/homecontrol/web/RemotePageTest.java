package dev.andre.homecontrol.web;

import dev.andre.homecontrol.adapters.androidtv.AndroidTvSettings;
import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.DeviceStatus;
import dev.andre.homecontrol.core.DiscoveredDevice;
import dev.andre.homecontrol.device.DeviceManager;
import dev.andre.homecontrol.adapters.androidtv.PairingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({RemoteController.class, SetupController.class})
class RemotePageTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    DeviceManager sessions;

    @MockitoBean
    PairingService pairing;

    @MockitoBean
    DeviceStateBroadcaster broadcaster;

    @Test
    void rendersTheRemoteWithCurrentAppButWithoutLauncherControls() throws Exception {
        given(sessions.defaultDevice()).willReturn(Optional.of(AndroidTvSettings.device(
                "living-room", "Living Room Shield", "192.168.1.50", 6466, null, Instant.now())));
        given(sessions.state(any())).willReturn(new DeviceState(
                DeviceStatus.CONNECTED, true, "com.netflix.ninja", 12, 100, false, Instant.now()));

        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/key/DPAD_UP")))
                .andExpect(content().string(containsString("com.netflix.ninja")))
                .andExpect(content().string(not(containsString("/apps/"))))
                .andExpect(content().string(not(containsString("Add current app"))))
                .andExpect(content().string(not(containsString("id=\"app-list\""))));
    }

    @Test
    void rendersTheSetupPageWithDiscoveredDevicesAndManualEntry() throws Exception {
        given(sessions.discovered()).willReturn(List.of(
                new DiscoveredDevice("androidtv", "Living Room Shield", "192.168.1.50", 6466)));
        given(sessions.defaultDevice()).willReturn(Optional.of(AndroidTvSettings.device(
                "living-room", "Living Room Shield", "192.168.1.50", 6466,
                null, Instant.now())));
        given(pairing.inProgress()).willReturn(false);

        mockMvc.perform(get("/setup"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Living Room Shield")))
                .andExpect(content().string(containsString("removes the stored pairing credential")))
                .andExpect(content().string(containsString("pair again")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"host\"")));
    }
}

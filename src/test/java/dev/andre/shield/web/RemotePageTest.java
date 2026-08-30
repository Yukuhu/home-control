package dev.andre.shield.web;

import dev.andre.shield.device.DeviceSessionManager;
import dev.andre.shield.device.DeviceState;
import dev.andre.shield.device.DeviceStatus;
import dev.andre.shield.device.PairingService;
import dev.andre.shield.discovery.MdnsDiscovery;
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
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({RemoteController.class, SetupController.class})
class RemotePageTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    DeviceSessionManager sessions;

    @MockitoBean
    PairingService pairing;

    @MockitoBean
    MdnsDiscovery discovery;

    @MockitoBean
    DeviceStateBroadcaster broadcaster;

    @Test
    void rendersTheRemoteWithCurrentAppButWithoutLauncherControls() throws Exception {
        given(sessions.state()).willReturn(new DeviceState(
                DeviceStatus.CONNECTED, true, "com.netflix.ninja", 12, 100, false, Instant.now()));
        given(sessions.activeDevice()).willReturn(Optional.empty());

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
        given(discovery.devices()).willReturn(List.of(
                new dev.andre.shield.discovery.DiscoveredDevice("Living Room Shield", "192.168.1.50", 6466)));
        given(sessions.activeDevice()).willReturn(Optional.empty());
        given(pairing.inProgress()).willReturn(false);

        mockMvc.perform(get("/setup"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Living Room Shield")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"host\"")));
    }
}

package dev.andre.shield.web;

import dev.andre.shield.device.DeviceSessionManager;
import dev.andre.shield.device.PairingService;
import dev.andre.shield.discovery.MdnsDiscovery;
import dev.andre.shield.storage.StorageException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.AccessDeniedException;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(SetupController.class)
class SetupControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    MdnsDiscovery discovery;

    @MockitoBean
    PairingService pairing;

    @MockitoBean
    DeviceSessionManager sessions;

    @Test
    void showsAnActionableStorageErrorBeforePairing() throws Exception {
        given(discovery.devices()).willReturn(List.of());
        given(sessions.activeDevice()).willReturn(Optional.empty());
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
}

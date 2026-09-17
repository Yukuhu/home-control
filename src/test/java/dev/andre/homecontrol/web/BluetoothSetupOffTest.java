package dev.andre.homecontrol.web;

import dev.andre.homecontrol.adapters.androidtv.PairingService;
import dev.andre.homecontrol.device.DeviceManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** With the module off, {@code BluetoothSetupController} and {@code BluetoothSetupAdvice} are not wired up at all. */
@WebMvcTest(SetupController.class)
class BluetoothSetupOffTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    PairingService pairing;

    @MockitoBean
    DeviceManager devices;

    @BeforeEach
    void defaults() {
        given(devices.devices()).willReturn(List.of());
        given(devices.pairable()).willReturn(List.of());
        given(devices.addable()).willReturn(List.of());
    }

    @Test
    void theSectionIsAbsent() throws Exception {
        mockMvc.perform(get("/setup")).andExpect(status().isOk())
                .andExpect(content().string(not(containsString("id=\"bluetooth\""))));
    }

    @Test
    void thePostEndpointsDoNotExist() throws Exception {
        mockMvc.perform(post("/setup/bluetooth/scan")).andExpect(status().isNotFound());
    }
}

package dev.andre.homecontrol.web;

import dev.andre.homecontrol.core.Action;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.core.DeviceOfflineException;
import dev.andre.homecontrol.device.DeviceManager;
import dev.andre.homecontrol.adapters.androidtv.PairingService;
import dev.andre.homecontrol.core.RemoteKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RemoteController.class)
class RemoteControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    DeviceManager sessions;

    @MockitoBean
    PairingService pairing;

    @MockitoBean
    DeviceStateBroadcaster broadcaster;

    @BeforeEach
    void setUp() {
        given(sessions.defaultDevice()).willReturn(Optional.of(new Device(
                "shield", "Shield", DeviceKind.ANDROID_TV, "127.0.0.1", Map.of(), Instant.now())));
    }

    @Test
    void sendsAKeyPress() throws Exception {
        mockMvc.perform(post("/key/DPAD_UP")).andExpect(status().isNoContent());

        verify(sessions).execute(eq("shield"), eq(new Action.PressKey(RemoteKey.DPAD_UP)));
    }

    @Test
    void rejectsAnUnknownKey() throws Exception {
        mockMvc.perform(post("/key/EJECT_TAPE")).andExpect(status().isBadRequest());
    }

    @Test
    void reportsConflictWhenTheDeviceIsOffline() throws Exception {
        willThrow(new DeviceOfflineException("offline")).given(sessions).execute(eq("shield"), any());

        mockMvc.perform(post("/key/HOME")).andExpect(status().isConflict());
    }

    @Test
    void reportsConflictWhenNoDeviceIsPaired() throws Exception {
        given(sessions.defaultDevice()).willReturn(Optional.empty());

        mockMvc.perform(post("/key/HOME")).andExpect(status().isConflict());
    }

    @Test
    void removedLauncherEndpointsHaveNoHandler() throws Exception {
        mockMvc.perform(post("/apps/current")).andExpect(status().isNotFound());
        mockMvc.perform(post("/apps/netflix/launch")).andExpect(status().isNotFound());
    }
}

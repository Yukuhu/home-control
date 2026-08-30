package dev.andre.shield.web;

import dev.andre.shield.device.DeviceOfflineException;
import dev.andre.shield.device.DeviceSession;
import dev.andre.shield.device.DeviceSessionManager;
import dev.andre.shield.device.PairingService;
import dev.andre.shield.discovery.MdnsDiscovery;
import dev.andre.shield.protocol.RemoteKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
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
    DeviceSessionManager sessions;

    @MockitoBean
    PairingService pairing;

    @MockitoBean
    MdnsDiscovery discovery;

    @MockitoBean
    DeviceStateBroadcaster broadcaster;

    DeviceSession session;

    @BeforeEach
    void setUp() {
        session = org.mockito.Mockito.mock(DeviceSession.class);
        given(sessions.active()).willReturn(Optional.of(session));
    }

    @Test
    void sendsAKeyPress() throws Exception {
        mockMvc.perform(post("/key/DPAD_UP")).andExpect(status().isNoContent());

        verify(session).sendKey(RemoteKey.DPAD_UP);
    }

    @Test
    void rejectsAnUnknownKey() throws Exception {
        mockMvc.perform(post("/key/EJECT_TAPE")).andExpect(status().isBadRequest());
    }

    @Test
    void reportsConflictWhenTheDeviceIsOffline() throws Exception {
        willThrow(new DeviceOfflineException("offline")).given(session).sendKey(any());

        mockMvc.perform(post("/key/HOME")).andExpect(status().isConflict());
    }

    @Test
    void reportsConflictWhenNoDeviceIsPaired() throws Exception {
        given(sessions.active()).willReturn(Optional.empty());

        mockMvc.perform(post("/key/HOME")).andExpect(status().isConflict());
    }

    @Test
    void removedLauncherEndpointsHaveNoHandler() throws Exception {
        mockMvc.perform(post("/apps/current")).andExpect(status().isNotFound());
        mockMvc.perform(post("/apps/netflix/launch")).andExpect(status().isNotFound());
    }
}

package dev.andre.shield.web;

import dev.andre.shield.apps.AppCatalog;
import dev.andre.shield.apps.AppEntry;
import dev.andre.shield.device.DeviceOfflineException;
import dev.andre.shield.device.DeviceSession;
import dev.andre.shield.device.DeviceSessionManager;
import dev.andre.shield.device.DeviceState;
import dev.andre.shield.device.DeviceStatus;
import dev.andre.shield.device.PairingService;
import dev.andre.shield.discovery.MdnsDiscovery;
import dev.andre.shield.protocol.RemoteKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(RemoteController.class)
class RemoteControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    DeviceSessionManager sessions;

    @MockitoBean
    AppCatalog apps;

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
    void launchesAnAppByItsCatalogId() throws Exception {
        given(apps.byId("netflix")).willReturn(
                Optional.of(new AppEntry("netflix", "Netflix", "com.netflix.ninja", null)));

        mockMvc.perform(post("/apps/netflix/launch")).andExpect(status().isNoContent());

        verify(session).launchAppLink("market://launch?id=com.netflix.ninja");
    }

    @Test
    void returnsNotFoundForAnUnknownApp() throws Exception {
        given(apps.byId("betamax")).willReturn(Optional.empty());

        mockMvc.perform(post("/apps/betamax/launch")).andExpect(status().isNotFound());
    }

    @Test
    void addsTheCurrentlyRunningAppToTheCatalog() throws Exception {
        given(sessions.state()).willReturn(new DeviceState(
                DeviceStatus.CONNECTED, true, "com.example.player", 12, 100, false, Instant.now()));
        given(apps.addPackage("com.example.player")).willReturn(
                new AppEntry("com.example.player", "com.example.player", "com.example.player", null));

        mockMvc.perform(post("/apps/current"))
                .andExpect(status().isOk())
                .andExpect(view().name("remote :: app-list"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"app-list\"")));

        verify(apps).addPackage("com.example.player");
    }

    @Test
    void rejectsAddingACurrentAppWhenNoAppIsReported() throws Exception {
        given(sessions.state()).willReturn(new DeviceState(
                DeviceStatus.CONNECTED, true, null, 12, 100, false, Instant.now()));

        mockMvc.perform(post("/apps/current")).andExpect(status().isConflict());
    }
}

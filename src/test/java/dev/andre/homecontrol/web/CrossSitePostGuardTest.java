package dev.andre.homecontrol.web;

import dev.andre.homecontrol.adapters.androidtv.PairingService;
import dev.andre.homecontrol.core.Action;
import dev.andre.homecontrol.core.RemoteKey;
import dev.andre.homecontrol.device.DeviceManager;
import dev.andre.homecontrol.playback.PlaybackService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({DeviceController.class, SetupController.class})
class CrossSitePostGuardTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    DeviceManager devices;

    @MockitoBean
    PlaybackService playback;

    @MockitoBean
    PairingService pairing;

    @Test
    void rejectsACrossSiteCommand() throws Exception {
        mockMvc.perform(post("/devices/shield/key/POWER").header("Sec-Fetch-Site", "cross-site"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(devices);
    }

    @Test
    void rejectsACrossSiteSetupChange() throws Exception {
        mockMvc.perform(post("/setup/forget").param("id", "shield").header("Sec-Fetch-Site", "cross-site"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(devices, pairing);
    }

    @ParameterizedTest
    @ValueSource(strings = {"same-origin", "none"})
    void letsAPostFromThisAppOrTypedByTheUserThrough(String site) throws Exception {
        mockMvc.perform(post("/devices/shield/key/HOME").header("Sec-Fetch-Site", site))
                .andExpect(status().isNoContent());

        verify(devices).execute("shield", new Action.PressKey(RemoteKey.HOME));
    }

    @Test
    void letsAPostWithoutFetchMetadataThrough() throws Exception {
        // Older browsers and scripts on the LAN (curl, home automation) send no Sec-Fetch-Site.
        mockMvc.perform(post("/devices/shield/key/HOME")).andExpect(status().isNoContent());
    }

    @Test
    void leavesCrossSiteReadsAlone() throws Exception {
        mockMvc.perform(get("/setup").header("Sec-Fetch-Site", "cross-site")).andExpect(status().isOk());
    }
}

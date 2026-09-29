package dev.andre.homecontrol.web;

import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.core.DiscoveredDevice;
import dev.andre.homecontrol.core.PromptPairingResult;
import dev.andre.homecontrol.testsupport.WebSliceTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

class PromptPairingSetupTest extends WebSliceTest {

    @Autowired
    MockMvc mockMvc;

    private static Device tv(String id) {
        return new Device(id, "LG", DeviceKind.WEBOS, "192.168.1.60", Map.of("webos", Map.of()), Instant.now());
    }

    @BeforeEach
    void noDevicesUnlessATestSaysOtherwise() {
        given(devices.devices()).willReturn(List.of());
        given(enrollment.pairable()).willReturn(List.of());
        given(enrollment.addable()).willReturn(List.of());
    }

    @Test
    void aDiscoveredTvGetsAPromptPairingForm() throws Exception {
        given(enrollment.pairable()).willReturn(List.of(new DiscoveredDevice("webos", "[LG] webOS TV", "192.168.1.60", 3000)));

        String html = mockMvc.perform(get("/setup"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("action=\"/setup/prompt-pair\"")))
                .andExpect(content().string(containsString("Accept the request on the TV.")))
                .andReturn().getResponse().getContentAsString();

        assertThat(html).containsPattern(Pattern.compile("name=\"adapter\"\\s+value=\"webos\""));
    }

    @Test
    void anAndroidTvDiscoveryKeepsTheCodePairingForm() throws Exception {
        given(enrollment.pairable()).willReturn(List.of(new DiscoveredDevice("androidtv", "Shield", "192.168.1.50", 6466)));

        String html = mockMvc.perform(get("/setup")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).containsPattern(Pattern.compile(
                "action=\"/setup/pair\">\\s*<input type=\"hidden\" name=\"adapter\" value=\"androidtv\""));
    }

    @Test
    void manualEntryOffersEveryPromptPairingAdapter() throws Exception {
        String html = mockMvc.perform(get("/setup")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).containsPattern(Pattern.compile("<select name=\"adapter\"[^>]*>\\s*<option value=\"webos\">LG webOS TV</option>"));
    }

    @Test
    void pairingRedirectsToTheNewDevice() throws Exception {
        promptPairing.willAnswer(new PromptPairingResult.Paired(tv("tv")));

        mockMvc.perform(post("/setup/prompt-pair").param("adapter", "webos").param("host", "192.168.1.60").param("name", "LG"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/?device=tv"));

        assertThat(promptPairing.host()).isEqualTo("192.168.1.60");
        assertThat(promptPairing.name()).isEqualTo("LG");
    }

    @Test
    void aDeclinedPairingShowsTheReason() throws Exception {
        promptPairing.willAnswer(new PromptPairingResult.Declined("The TV declined the pairing request"));

        mockMvc.perform(post("/setup/prompt-pair").param("adapter", "webos").param("host", "192.168.1.60"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("The TV declined the pairing request")));
    }

    @Test
    void aFailedPairingShowsTheReason() throws Exception {
        promptPairing.willAnswer(new PromptPairingResult.Failed("Could not reach an LG webOS TV at 10.0.0.9: refused"));

        mockMvc.perform(post("/setup/prompt-pair").param("adapter", "webos").param("host", "10.0.0.9"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Could not reach an LG webOS TV at 10.0.0.9: refused")));
    }

    @Test
    void anUnknownAdapterIsExplained() throws Exception {
        mockMvc.perform(post("/setup/prompt-pair").param("adapter", "nope").param("host", "10.0.0.9"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Unknown device type nope")));
    }

    @Test
    void theMacFieldAppearsOnlyForDevicesThatWakeOnLan() throws Exception {
        given(devices.devices()).willReturn(List.of(tv("tv"), tv("box")));
        given(deviceSettings.wakesOnLan("tv")).willReturn(true);
        given(deviceSettings.wakeOnLanMac("tv")).willReturn(Optional.of("A8:23:FE:01:02:03"));
        given(deviceSettings.wakesOnLan("box")).willReturn(false);

        mockMvc.perform(get("/setup"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/setup/devices/tv/mac")))
                .andExpect(content().string(containsString("A8:23:FE:01:02:03")))
                .andExpect(content().string(not(containsString("/setup/devices/box/mac"))));
    }

    @Test
    void savesAHandEnteredMac() throws Exception {
        given(devices.device("tv")).willReturn(Optional.of(tv("tv")));

        mockMvc.perform(post("/setup/devices/tv/mac").param("mac", "a8-23-fe-01-02-03"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/setup"));

        verify(deviceSettings).setWakeOnLanMac("tv", "a8-23-fe-01-02-03");
    }

    @Test
    void anInvalidMacShowsTheReason() throws Exception {
        given(devices.device("tv")).willReturn(Optional.of(tv("tv")));
        willThrow(new IllegalArgumentException("Not a MAC address: nope")).given(deviceSettings).setWakeOnLanMac("tv", "nope");

        mockMvc.perform(post("/setup/devices/tv/mac").param("mac", "nope"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Not a MAC address: nope")));
    }

    @Test
    void theMacOfAnUnknownDeviceIsNotFound() throws Exception {
        given(devices.device("ghost")).willReturn(Optional.empty());

        mockMvc.perform(post("/setup/devices/ghost/mac").param("mac", "a8-23-fe-01-02-03"))
                .andExpect(status().isNotFound());
    }

    @Test
    void theClientKeyNeverReachesThePage() throws Exception {
        given(devices.devices()).willReturn(List.of(new Device("tv", "LG", DeviceKind.WEBOS, "192.168.1.60",
                Map.of("webos", Map.of("clientKey", "5f1c0d7e2b9a4c3d")), Instant.now())));
        given(deviceSettings.wakesOnLan("tv")).willReturn(true);

        mockMvc.perform(get("/setup"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("5f1c0d7e2b9a4c3d"))));
    }
}

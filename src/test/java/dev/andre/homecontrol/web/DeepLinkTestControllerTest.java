package dev.andre.homecontrol.web;

import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.core.DeviceNotFoundException;
import dev.andre.homecontrol.core.DeviceOfflineException;
import dev.andre.homecontrol.core.UnsupportedActionException;
import dev.andre.homecontrol.playback.DeepLinkTestResult;
import dev.andre.homecontrol.testsupport.WebSliceTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DeepLinkTestControllerTest extends WebSliceTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void rendersTheOutcomeAsAnEscapedFragment() throws Exception {
        given(devices.device("lg")).willReturn(Optional.of(
                new Device("lg", "LG TV", DeviceKind.WEBOS, "10.0.0.60", Map.of("webos", Map.of()), Instant.now())));
        given(deepLinkTests.run("lg")).willReturn(new DeepLinkTestResult(DeepLinkTestResult.Outcome.APP_CHANGED,
                "com.webos.app.home", "youtube.leanback.v4", "LG <b>TV</b> switched"));

        mockMvc.perform(post("/setup/devices/lg/deep-link-test"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string("<p class=\"deep-link-result app-changed\">LG &lt;b&gt;TV&lt;/b&gt; switched</p>"));
    }

    @Test
    void anUnknownDeviceIsNotFound() throws Exception {
        given(deepLinkTests.run("ghost")).willThrow(new DeviceNotFoundException("No device with id ghost"));

        mockMvc.perform(post("/setup/devices/ghost/deep-link-test"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andExpect(content().string("No device with id ghost"));
    }

    @Test
    void anOfflineDeviceIsAFailedResultNotAnError() throws Exception {
        given(deepLinkTests.run("lg")).willThrow(new DeviceOfflineException("LG TV is offline"));

        mockMvc.perform(post("/setup/devices/lg/deep-link-test"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string("<p class=\"deep-link-result failed\">LG TV is offline</p>"));
    }

    @Test
    void aDeviceWithoutAppLinksIsUnprocessable() throws Exception {
        given(devices.device("speaker")).willReturn(Optional.of(
                new Device("speaker", "Speaker", DeviceKind.CAST, "10.0.0.9", Map.of("cast", Map.of()), Instant.now())));
        given(deepLinkTests.run("speaker")).willThrow(new UnsupportedActionException("Speaker cannot open app links"));

        mockMvc.perform(post("/setup/devices/speaker/deep-link-test"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().string("Speaker cannot open app links"));
    }
}

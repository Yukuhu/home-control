package dev.andre.homecontrol.web;

import dev.andre.homecontrol.core.Action;
import dev.andre.homecontrol.core.DeviceNotFoundException;
import dev.andre.homecontrol.core.DeviceOfflineException;
import dev.andre.homecontrol.core.RemoteKey;
import dev.andre.homecontrol.core.UnsupportedActionException;
import dev.andre.homecontrol.core.playback.Route;
import dev.andre.homecontrol.core.playback.UnroutableException;
import dev.andre.homecontrol.device.DeviceManager;
import dev.andre.homecontrol.playback.PlaybackService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DeviceController.class)
class DeviceControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    DeviceManager devices;

    @MockitoBean
    PlaybackService playback;

    @Test
    void sendsAKeyToTheAddressedDevice() throws Exception {
        mockMvc.perform(post("/devices/shield/key/DPAD_UP")).andExpect(status().isNoContent());

        verify(devices).execute("shield", new Action.PressKey(RemoteKey.DPAD_UP));
    }

    @Test
    void rejectsAnUnknownKey() throws Exception {
        mockMvc.perform(post("/devices/shield/key/EJECT_TAPE")).andExpect(status().isBadRequest());

        verifyNoInteractions(devices);
    }

    @Test
    void reportsAnUnknownDeviceAsNotFound() throws Exception {
        willThrow(new DeviceNotFoundException("No device with id ghost")).given(devices).execute(eq("ghost"), any());

        mockMvc.perform(post("/devices/ghost/key/HOME"))
                .andExpect(status().isNotFound())
                .andExpect(content().string("No device with id ghost"));
    }

    @Test
    void reportsConflictWhenTheDeviceIsOffline() throws Exception {
        willThrow(new DeviceOfflineException("offline")).given(devices).execute(eq("shield"), any());

        mockMvc.perform(post("/devices/shield/key/HOME"))
                .andExpect(status().isConflict())
                .andExpect(content().string("offline"));
    }

    @Test
    void reportsUnprocessableWhenTheDeviceCannotDoThat() throws Exception {
        willThrow(new UnsupportedActionException("Shield cannot perform that")).given(devices).execute(eq("shield"), any());

        mockMvc.perform(post("/devices/shield/key/HOME")).andExpect(status().isUnprocessableContent());
    }

    @Test
    void playsAPastedLinkAndDescribesTheRoute() throws Exception {
        URI uri = URI.create("https://www.youtube.com/watch?v=abc");
        given(playback.play(any(), eq("shield"))).willReturn(new Route.OpenAppLink(uri, "youtube"));

        mockMvc.perform(post("/devices/shield/play").param("uri", uri.toString()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/plain"))
                .andExpect(content().string("Open in the YouTube app"));
    }

    @Test
    void rejectsALinkThatIsNotHttp() throws Exception {
        mockMvc.perform(post("/devices/shield/play").param("uri", "ftp://nope"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("Only http and https links can be opened on a device"));
    }

    @Test
    void rejectsAnOverlongLinkWithoutPlayingIt() throws Exception {
        mockMvc.perform(post("/devices/shield/play").param("uri", "https://example.org/" + "a".repeat(2048)))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("That link is too long"));

        verifyNoInteractions(playback);
    }

    @Test
    void anUnrelatedIllegalArgumentIsNotReportedAsABadLink() throws Exception {
        given(playback.play(any(), eq("shield"))).willThrow(new IllegalArgumentException("programming error"));

        // Unhandled, so MockMvc rethrows it instead of rendering a 400 "bad link".
        assertThatThrownBy(() -> mockMvc.perform(post("/devices/shield/play").param("uri", "https://example.org/a")))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reportsPlayOnAnUnknownDeviceAsNotFound() throws Exception {
        given(playback.play(any(), eq("ghost"))).willThrow(new DeviceNotFoundException("No device with id ghost"));

        mockMvc.perform(post("/devices/ghost/play").param("uri", "https://example.org/a"))
                .andExpect(status().isNotFound());
    }

    @Test
    void reportsPlayOnAnOfflineDeviceAsConflict() throws Exception {
        given(playback.play(any(), eq("shield"))).willThrow(new DeviceOfflineException("Shield is not connected"));

        mockMvc.perform(post("/devices/shield/play").param("uri", "https://example.org/a"))
                .andExpect(status().isConflict())
                .andExpect(content().string("Shield is not connected"));
    }

    @Test
    void explainsWhyALinkCannotBePlayed() throws Exception {
        given(playback.play(any(), eq("shield")))
                .willThrow(new UnroutableException("Shield: this device cannot open app links"));

        mockMvc.perform(post("/devices/shield/play").param("uri", "https://example.org/a"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().string("Shield: this device cannot open app links"));
    }
}

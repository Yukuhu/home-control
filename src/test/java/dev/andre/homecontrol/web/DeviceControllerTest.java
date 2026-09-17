package dev.andre.homecontrol.web;

import dev.andre.homecontrol.core.Action;
import dev.andre.homecontrol.core.ActionFailedException;
import dev.andre.homecontrol.core.DeviceNotFoundException;
import dev.andre.homecontrol.core.DeviceOfflineException;
import dev.andre.homecontrol.core.KeyPress;
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
import java.util.Map;

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
    void repeatsAShortPress() throws Exception {
        mockMvc.perform(post("/devices/shield/key/DPAD_RIGHT").param("repeat", "3"))
                .andExpect(status().isNoContent());

        verify(devices, org.mockito.Mockito.times(3))
                .execute("shield", new Action.PressKey(RemoteKey.DPAD_RIGHT, KeyPress.SHORT));
    }

    @Test
    void sendsLongPressEdges() throws Exception {
        mockMvc.perform(post("/devices/shield/key/DPAD_CENTER").param("press", "start_long"))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/devices/shield/key/DPAD_CENTER").param("press", "end_long"))
                .andExpect(status().isNoContent());

        verify(devices).execute("shield", new Action.PressKey(RemoteKey.DPAD_CENTER, KeyPress.START_LONG));
        verify(devices).execute("shield", new Action.PressKey(RemoteKey.DPAD_CENTER, KeyPress.END_LONG));
    }

    @Test
    void rejectsInvalidRepeatAndPress() throws Exception {
        mockMvc.perform(post("/devices/shield/key/DPAD_RIGHT").param("repeat", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("repeat must be 1 to 4"));
        mockMvc.perform(post("/devices/shield/key/DPAD_RIGHT").param("repeat", "5"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("repeat must be 1 to 4"));
        mockMvc.perform(post("/devices/shield/key/DPAD_RIGHT").param("press", "double"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("Unknown press double"));
        mockMvc.perform(post("/devices/shield/key/DPAD_CENTER").param("press", "start_long").param("repeat", "2"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("A long press cannot repeat"));
        mockMvc.perform(post("/devices/shield/key/VOLUME_UP").param("press", "start_long"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("VOLUME_UP has no long press"));

        verifyNoInteractions(devices);
    }

    @Test
    void stopsAtTheFirstFailure() throws Exception {
        willThrow(new DeviceOfflineException("offline")).given(devices).execute(eq("shield"), any());

        mockMvc.perform(post("/devices/shield/key/DPAD_RIGHT").param("repeat", "3"))
                .andExpect(status().isConflict());

        verify(devices, org.mockito.Mockito.times(1)).execute(eq("shield"), any());
    }

    @Test
    void pausesAndResumes() throws Exception {
        mockMvc.perform(post("/devices/shield/pause")).andExpect(status().isNoContent());
        verify(devices).execute("shield", new Action.Pause());

        mockMvc.perform(post("/devices/shield/resume")).andExpect(status().isNoContent());
        verify(devices).execute("shield", new Action.Resume());

        willThrow(new DeviceNotFoundException("No device with id ghost")).given(devices).execute(eq("ghost"), any());
        mockMvc.perform(post("/devices/ghost/pause")).andExpect(status().isNotFound());
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
    void describesACastRoute() throws Exception {
        given(playback.play(any(), eq("shield"))).willReturn(new Route.Cast("CC1AD845", Map.of()));

        mockMvc.perform(post("/devices/shield/play").param("uri", "http://nas.local/films/bunny.mp4"))
                .andExpect(status().isOk())
                .andExpect(content().string("Cast with the Default Media Receiver"));
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

    @Test
    void setsTheVolume() throws Exception {
        mockMvc.perform(post("/devices/shield/volume").param("level", "40")).andExpect(status().isNoContent());

        verify(devices).execute("shield", new Action.SetVolume(40));
    }

    @Test
    void rejectsAVolumeOutsideZeroToHundred() throws Exception {
        mockMvc.perform(post("/devices/shield/volume").param("level", "150"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("Volume must be between 0 and 100"));

        verifyNoInteractions(devices);
    }

    @Test
    void mutesAndStops() throws Exception {
        mockMvc.perform(post("/devices/shield/mute").param("muted", "true")).andExpect(status().isNoContent());
        mockMvc.perform(post("/devices/shield/stop")).andExpect(status().isNoContent());

        verify(devices).execute("shield", new Action.Mute(true));
        verify(devices).execute("shield", new Action.Stop());
    }

    @Test
    void volumeForAnUnknownDeviceIsNotFound() throws Exception {
        willThrow(new DeviceNotFoundException("No device with id ghost")).given(devices).execute(eq("ghost"), any());

        mockMvc.perform(post("/devices/ghost/volume").param("level", "10"))
                .andExpect(status().isNotFound())
                .andExpect(content().string("No device with id ghost"));
    }

    @Test
    void stoppingAnOfflineDeviceIsAConflict() throws Exception {
        willThrow(new DeviceOfflineException("Kitchen is not connected")).given(devices).execute(eq("kitchen"), any());

        mockMvc.perform(post("/devices/kitchen/stop")).andExpect(status().isConflict());
    }

    @Test
    void mutingADeviceWithoutVolumeControlIsUnprocessable() throws Exception {
        willThrow(new UnsupportedActionException("Bedroom cannot perform Mute")).given(devices).execute(eq("bedroom"), any());

        mockMvc.perform(post("/devices/bedroom/mute").param("muted", "false")).andExpect(status().isUnprocessableContent());
    }

    @Test
    void reportsBadGatewayWhenTheDeviceRefusesOrDoesNotAnswer() throws Exception {
        willThrow(new ActionFailedException("Kitchen did not answer in time when asked to set the volume"))
                .given(devices).execute(eq("shield"), any());

        mockMvc.perform(post("/devices/shield/volume").param("level", "10"))
                .andExpect(status().isBadGateway())
                .andExpect(content().string("Kitchen did not answer in time when asked to set the volume"));
    }

    @Test
    void switchesTheInputOfTheAddressedDevice() throws Exception {
        mockMvc.perform(post("/devices/shield/input/HDMI_2")).andExpect(status().isNoContent());

        verify(devices).execute("shield", new Action.SelectInput("HDMI_2"));
    }

    @Test
    void anInputOfAnUnknownDeviceIsNotFound() throws Exception {
        willThrow(new DeviceNotFoundException("No device with id ghost")).given(devices).execute(eq("ghost"), any());

        mockMvc.perform(post("/devices/ghost/input/HDMI_1")).andExpect(status().isNotFound());
    }

    @Test
    void anInputOfAnOfflineTvIsAConflictAndOfADeviceWithoutInputsUnprocessable() throws Exception {
        willThrow(new DeviceOfflineException("LG TV is not connected")).given(devices).execute(eq("lg"), any());
        willThrow(new UnsupportedActionException("Android TV does not list its inputs")).given(devices).execute(eq("shield"), any());

        mockMvc.perform(post("/devices/lg/input/HDMI_1")).andExpect(status().isConflict());
        mockMvc.perform(post("/devices/shield/input/HDMI_1")).andExpect(status().isUnprocessableContent());
    }

    @Test
    void anIllegalArgumentFromTheDeviceIsNotReportedAsABadVolume() throws Exception {
        willThrow(new IllegalArgumentException("programming error")).given(devices).execute(eq("shield"), any());

        assertThatThrownBy(() -> mockMvc.perform(post("/devices/shield/volume").param("level", "10")))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }
}

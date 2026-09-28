package dev.andre.homecontrol.web;

import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.core.content.ContentSource;
import dev.andre.homecontrol.testsupport.WebSliceTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The JSON endpoints answer an unknown device, source or item, or bad skips, with the same plain text as the others. */
class ContentPlayErrorBodiesTest extends WebSliceTest {

    @Autowired
    MockMvc mockMvc;

    private final Device living = new Device("living", "Living Room", DeviceKind.ANDROID_TV, "10.0.0.5",
            Map.of("androidtv", Map.of()), Instant.now());

    @Test
    void anAttemptForAnUnknownDeviceSourceOrItemIsAPlainText404() throws Exception {
        given(devices.device("ghost")).willReturn(Optional.empty());
        mockMvc.perform(post("/devices/ghost/play-attempt").param("source", "jellyfin").param("item", "x")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andExpect(content().string("No device with id ghost"));

        given(devices.device("living")).willReturn(Optional.of(living));
        given(sources.find("nope")).willReturn(Optional.empty());
        mockMvc.perform(post("/devices/living/play-attempt").param("source", "nope").param("item", "x"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andExpect(content().string("No content source nope"));

        ContentSource jellyfin = mock(ContentSource.class);
        given(sources.find("jellyfin")).willReturn(Optional.of(jellyfin));
        given(jellyfin.item("missing")).willReturn(Optional.empty());
        mockMvc.perform(post("/devices/living/play-attempt").param("source", "jellyfin").param("item", "missing"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andExpect(content().string("No such item"));
    }

    @Test
    void tooManySkipsAreAPlainText400BeforeTheDeviceIsLookedUp() throws Exception {
        mockMvc.perform(post("/devices/living/play-attempt").param("source", "jellyfin").param("item", "x")
                        .param("skip", "a", "b", "c", "d", "e", "f", "g", "h", "i"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andExpect(content().string("Too many or too long route keys to skip"));

        verifyNoInteractions(devices, playback);
    }

    @Test
    void aPreviewForAnUnknownDeviceIsAPlainText404() throws Exception {
        given(devices.device(any())).willReturn(Optional.empty());

        mockMvc.perform(get("/devices/ghost/route-preview").param("source", "jellyfin").param("item", "x")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andExpect(content().string("No device with id ghost"));
    }
}

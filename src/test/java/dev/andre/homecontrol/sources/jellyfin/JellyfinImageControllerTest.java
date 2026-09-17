package dev.andre.homecontrol.sources.jellyfin;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.net.URI;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.verify;

@WebMvcTest(JellyfinImageController.class)
class JellyfinImageControllerTest {

    private static final String ITEM_ID = "b1c2d3e4f5061728394a5b6c7d8e9f01";
    private static final URI SERVER_URL = URI.create("http://nas:8096");

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    JellyfinClient client;

    @MockitoBean
    JellyfinSetupService setup;

    private static JellyfinSettings settings() {
        return new JellyfinSettings(SERVER_URL, SERVER_URL, "server-id", "nas", "10.9.0",
                FakeJellyfinServer.USER_ID, "andre", JellyfinSettings.AuthMode.PASSWORD, "device-id",
                JellyfinSettings.DEFAULT_CAST_RECEIVER_ID, Map.of());
    }

    @Test
    void servesATaggedImageAsImmutable() throws Exception {
        given(setup.settings()).willReturn(Optional.of(settings()));
        given(client.image(SERVER_URL, ITEM_ID, "Primary", "c0ffeec0ffee", 480))
                .willReturn(Optional.of(new JellyfinClient.Image("image/jpeg", new byte[] {1, 2, 3})));

        mockMvc.perform(get("/sources/jellyfin/images/" + ITEM_ID + "/Primary").param("tag", "c0ffeec0ffee"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/jpeg"))
                .andExpect(header().string("Cache-Control", "private, max-age=31536000, immutable"))
                .andExpect(content().bytes(new byte[] {1, 2, 3}));

        verify(client).image(SERVER_URL, ITEM_ID, "Primary", "c0ffeec0ffee", 480);
    }

    @Test
    void anUntaggedImageIsCachedBriefly() throws Exception {
        given(setup.settings()).willReturn(Optional.of(settings()));
        given(client.image(eq(SERVER_URL), eq(ITEM_ID), eq("Primary"), isNull(), eq(480)))
                .willReturn(Optional.of(new JellyfinClient.Image("image/jpeg", new byte[] {1})));

        mockMvc.perform(get("/sources/jellyfin/images/" + ITEM_ID + "/Primary"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "private, max-age=3600"));
    }

    @Test
    void widthIsClamped() throws Exception {
        given(setup.settings()).willReturn(Optional.of(settings()));
        given(client.image(eq(SERVER_URL), eq(ITEM_ID), eq("Primary"), isNull(), org.mockito.ArgumentMatchers.anyInt()))
                .willReturn(Optional.of(new JellyfinClient.Image("image/jpeg", new byte[] {1})));

        mockMvc.perform(get("/sources/jellyfin/images/" + ITEM_ID + "/Primary").param("width", "99999"))
                .andExpect(status().isOk());
        verify(client).image(SERVER_URL, ITEM_ID, "Primary", null, 1920);

        mockMvc.perform(get("/sources/jellyfin/images/" + ITEM_ID + "/Primary").param("width", "0"))
                .andExpect(status().isOk());
        verify(client).image(SERVER_URL, ITEM_ID, "Primary", null, 480);
    }

    @Test
    void rejectsIdsTypesAndTagsThatAreNotJellyfinShaped() throws Exception {
        mockMvc.perform(get("/sources/jellyfin/images/..%2Fx/Primary")).andExpect(status().is4xxClientError());

        mockMvc.perform(get("/sources/jellyfin/images/" + ITEM_ID + "/Chapter")).andExpect(status().isBadRequest());

        mockMvc.perform(get("/sources/jellyfin/images/" + ITEM_ID + "/Primary").param("tag", "a b"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void missingConfigurationOrImageIs404AndUpstreamFailureIs502() throws Exception {
        given(setup.settings()).willReturn(Optional.empty());
        mockMvc.perform(get("/sources/jellyfin/images/" + ITEM_ID + "/Primary")).andExpect(status().isNotFound());

        given(setup.settings()).willReturn(Optional.of(settings()));
        given(client.image(eq(SERVER_URL), eq(ITEM_ID), eq("Primary"), isNull(), eq(480))).willReturn(Optional.empty());
        mockMvc.perform(get("/sources/jellyfin/images/" + ITEM_ID + "/Primary")).andExpect(status().isNotFound());

        given(client.image(eq(SERVER_URL), eq(ITEM_ID), eq("Primary"), isNull(), eq(480)))
                .willThrow(new JellyfinException(JellyfinException.Kind.BAD_RESPONSE, "Jellyfin sent no image"));
        mockMvc.perform(get("/sources/jellyfin/images/" + ITEM_ID + "/Primary")).andExpect(status().isBadGateway());
    }
}

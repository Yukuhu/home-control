package dev.andre.homecontrol.web;

import dev.andre.homecontrol.core.ActionFailedException;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.core.DeviceOfflineException;
import dev.andre.homecontrol.core.UnsupportedActionException;
import dev.andre.homecontrol.core.content.ContentSource;
import dev.andre.homecontrol.core.content.ContentSources;
import dev.andre.homecontrol.core.playback.ContentItem;
import dev.andre.homecontrol.core.playback.ContentKind;
import dev.andre.homecontrol.core.playback.Route;
import dev.andre.homecontrol.device.DeviceManager;
import dev.andre.homecontrol.playback.PlayAttempt;
import dev.andre.homecontrol.playback.PlaybackPreview;
import dev.andre.homecontrol.playback.PlaybackService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ContentPlayController.class)
class ContentPlayPreviewTest {

    private static final String ITEM_ID = "3f2a9c1e7b6d4e5f8a9b0c1d2e3f4a5b";

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    DeviceManager devices;

    @MockitoBean
    ContentSources sources;

    @MockitoBean
    PlaybackService playback;

    private final ContentSource jellyfin = mock(ContentSource.class);
    private final Device living = new Device("living", "Living Room", DeviceKind.ANDROID_TV, "10.0.0.5",
            Map.of("androidtv", Map.of()), Instant.now());
    private final ContentItem theItem = new ContentItem(ITEM_ID, "jellyfin", ContentKind.EPISODE,
            "Northern Lights", null, null, List.of());

    private void known() {
        given(devices.device("living")).willReturn(Optional.of(living));
        given(sources.find("jellyfin")).willReturn(Optional.of(jellyfin));
        given(jellyfin.item(ITEM_ID)).willReturn(Optional.of(theItem));
    }

    @Test
    void previewIsJsonWithTheRouteAndItsAlternatives() throws Exception {
        known();
        Route.OpenAppLink appLink = new Route.OpenAppLink(java.net.URI.create("https://www.youtube.com/watch?v=abc"), "youtube");
        Route.Cast cast = new Route.Cast("CC1AD845", Map.of());
        given(playback.preview(theItem, "living")).willReturn(new PlaybackPreview(living, List.of(appLink, cast), null));

        mockMvc.perform(get("/devices/living/route-preview").param("source", "jellyfin").param("item", ITEM_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deviceName").value("Living Room"))
                .andExpect(jsonPath("$.playable").value(true))
                .andExpect(jsonPath("$.route.key").value("app-link"))
                .andExpect(jsonPath("$.route.description").value("Open in the YouTube app"))
                .andExpect(jsonPath("$.route.optimistic").value(true))
                .andExpect(jsonPath("$.alternatives[0].key").value("cast:CC1AD845"))
                .andExpect(jsonPath("$.reason").doesNotExist());
        verify(playback, never()).attempt(any(), any(), any());
    }

    @Test
    void anUnroutablePreviewIsStill200() throws Exception {
        known();
        given(playback.preview(theItem, "living")).willReturn(new PlaybackPreview(living, List.of(), "x"));

        mockMvc.perform(get("/devices/living/route-preview").param("source", "jellyfin").param("item", ITEM_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.playable").value(false))
                .andExpect(jsonPath("$.route").doesNotExist())
                .andExpect(jsonPath("$.reason").value("x"));
    }

    @Test
    void previewUnknownsAre404() throws Exception {
        given(devices.device("ghost")).willReturn(Optional.empty());

        mockMvc.perform(get("/devices/ghost/route-preview").param("source", "nope").param("item", "x"))
                .andExpect(status().isNotFound())
                .andExpect(content().string("No device with id ghost"));

        given(devices.device("living")).willReturn(Optional.of(living));
        given(sources.find("nope")).willReturn(Optional.empty());

        mockMvc.perform(get("/devices/living/route-preview").param("source", "nope").param("item", "x"))
                .andExpect(status().isNotFound())
                .andExpect(content().string("No content source nope"));

        given(sources.find("jellyfin")).willReturn(Optional.of(jellyfin));
        given(jellyfin.item("missing")).willReturn(Optional.empty());

        mockMvc.perform(get("/devices/living/route-preview").param("source", "jellyfin").param("item", "missing"))
                .andExpect(status().isNotFound())
                .andExpect(content().string("No such item"));
    }

    @Test
    void aSuccessfulAttemptIs200() throws Exception {
        known();
        Route.OpenAppLink appLink = new Route.OpenAppLink(java.net.URI.create("https://www.youtube.com/watch?v=abc"), "youtube");
        given(playback.attempt(theItem, "living", Set.of())).willReturn(new PlayAttempt.Played(living, appLink, List.of()));

        mockMvc.perform(post("/devices/living/play-attempt").param("source", "jellyfin").param("item", ITEM_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.played").value(true))
                .andExpect(jsonPath("$.next").doesNotExist())
                .andExpect(jsonPath("$.message").value("Open in the YouTube app"));
    }

    @Test
    void aFailedAttemptNamesTheFailedRouteAndTheNextOne() throws Exception {
        known();
        Route.OpenAppLink appLink = new Route.OpenAppLink(java.net.URI.create("https://www.youtube.com/watch?v=abc"), "youtube");
        Route.Cast cast = new Route.Cast("CC1AD845", Map.of());
        given(playback.attempt(theItem, "living", Set.of())).willReturn(
                new PlayAttempt.Failed(living, appLink, List.of(cast), new ActionFailedException("Living Room refused")));

        mockMvc.perform(post("/devices/living/play-attempt").param("source", "jellyfin").param("item", ITEM_ID))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.route.key").value("app-link"))
                .andExpect(jsonPath("$.next.description").value("Cast with the Default Media Receiver"))
                .andExpect(jsonPath("$.message").value("Living Room refused"));
    }

    @Test
    void failureStatusFollowsTheCause() throws Exception {
        known();
        Route.OpenAppLink appLink = new Route.OpenAppLink(java.net.URI.create("https://www.youtube.com/watch?v=abc"), "youtube");
        given(playback.attempt(theItem, "living", Set.of()))
                .willReturn(new PlayAttempt.Failed(living, appLink, List.of(), new DeviceOfflineException("offline")));

        mockMvc.perform(post("/devices/living/play-attempt").param("source", "jellyfin").param("item", ITEM_ID))
                .andExpect(status().isConflict());

        given(playback.attempt(theItem, "living", Set.of()))
                .willReturn(new PlayAttempt.Failed(living, appLink, List.of(), new UnsupportedActionException("unsupported")));

        mockMvc.perform(post("/devices/living/play-attempt").param("source", "jellyfin").param("item", ITEM_ID))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void skipIsPassedThroughAndValidated() throws Exception {
        known();
        Route.OpenAppLink appLink = new Route.OpenAppLink(java.net.URI.create("https://www.youtube.com/watch?v=abc"), "youtube");
        given(playback.attempt(theItem, "living", Set.of("app-link", "cast:CC1AD845")))
                .willReturn(new PlayAttempt.Played(living, appLink, List.of()));

        mockMvc.perform(post("/devices/living/play-attempt").param("source", "jellyfin").param("item", ITEM_ID)
                        .param("skip", "app-link", "cast:CC1AD845"))
                .andExpect(status().isOk());
        verify(playback).attempt(theItem, "living", Set.of("app-link", "cast:CC1AD845"));

        String[] nine = new String[]{"a", "b", "c", "d", "e", "f", "g", "h", "i"};
        mockMvc.perform(post("/devices/living/play-attempt").param("source", "jellyfin").param("item", ITEM_ID)
                        .param("skip", nine))
                .andExpect(status().isBadRequest());

        String tooLong = "x".repeat(65);
        mockMvc.perform(post("/devices/living/play-attempt").param("source", "jellyfin").param("item", ITEM_ID)
                        .param("skip", tooLong))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unroutableAttemptIs422WithAMessage() throws Exception {
        known();
        given(playback.attempt(theItem, "living", Set.of("app-link")))
                .willReturn(new PlayAttempt.Unroutable(living, "no other way to play this"));

        mockMvc.perform(post("/devices/living/play-attempt").param("source", "jellyfin").param("item", ITEM_ID)
                        .param("skip", "app-link"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.message").value("Living Room: no other way to play this"));
    }

    @Test
    void noSecretReachesTheBrowser() throws Exception {
        known();
        Route.CastMessage message = new Route.CastMessage("F007D354", "urn:x-cast:com.connectsdk",
                Map.of("accessToken", "tok-123"), "the Jellyfin receiver");
        given(playback.preview(theItem, "living")).willReturn(new PlaybackPreview(living, List.of(message), null));

        String body = mockMvc.perform(get("/devices/living/route-preview").param("source", "jellyfin").param("item", ITEM_ID))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(body).doesNotContain("tok-123").doesNotContain("urn:x-cast");
    }

    @Test
    void thePlainTextEndpointsAreUnchanged() throws Exception {
        known();
        given(playback.plan(theItem, "living")).willReturn(new Route.OpenAppLink(
                java.net.URI.create("https://www.youtube.com/watch?v=abc"), "youtube"));

        mockMvc.perform(get("/devices/living/route").param("source", "jellyfin").param("item", ITEM_ID))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/plain"))
                .andExpect(content().string("Open in the YouTube app"));
    }
}

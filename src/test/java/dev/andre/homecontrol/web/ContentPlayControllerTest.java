package dev.andre.homecontrol.web;

import dev.andre.homecontrol.core.ActionFailedException;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.core.DeviceNotFoundException;
import dev.andre.homecontrol.core.DeviceOfflineException;
import dev.andre.homecontrol.core.content.ContentSource;
import dev.andre.homecontrol.core.content.ContentSourceException;
import dev.andre.homecontrol.core.content.ContentSources;
import dev.andre.homecontrol.core.playback.ContentItem;
import dev.andre.homecontrol.core.playback.ContentKind;
import dev.andre.homecontrol.core.playback.Route;
import dev.andre.homecontrol.core.playback.UnroutableException;
import dev.andre.homecontrol.device.DeviceManager;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ContentPlayController.class)
class ContentPlayControllerTest {

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
    private final Device shield = new Device("shield", "Shield", DeviceKind.ANDROID_TV, "10.0.0.5",
            Map.of("androidtv", Map.of()), Instant.now());
    private final ContentItem theItem = new ContentItem(ITEM_ID, "jellyfin", ContentKind.EPISODE,
            "Northern Lights", null, null, List.of());

    private void known() {
        given(devices.device("shield")).willReturn(Optional.of(shield));
        given(sources.find("jellyfin")).willReturn(Optional.of(jellyfin));
        given(jellyfin.item(ITEM_ID)).willReturn(Optional.of(theItem));
    }

    @Test
    void playsASourceItemAndDescribesTheRoute() throws Exception {
        known();
        given(playback.play(theItem, "shield")).willReturn(new Route.JellyfinSession("s1", "item-1", 0, "Android TV"));

        mockMvc.perform(post("/devices/shield/play").param("source", "jellyfin").param("item", ITEM_ID))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/plain"))
                .andExpect(content().string("Play in the open Jellyfin app (Android TV)"));

        verify(playback).play(theItem, "shield");
    }

    @Test
    void previewsTheRouteWithoutPlaying() throws Exception {
        known();
        given(playback.plan(theItem, "shield"))
                .willReturn(new Route.CastMessage("F007D354", "urn:x-cast:com.connectsdk", Map.of(), "the Jellyfin receiver"));

        mockMvc.perform(get("/devices/shield/route").param("source", "jellyfin").param("item", ITEM_ID))
                .andExpect(status().isOk())
                .andExpect(content().string("Cast with the Jellyfin receiver"));
        verify(playback, never()).play(any(), any());

        given(playback.plan(theItem, "shield")).willReturn(new Route.Unroutable("no Jellyfin app is open on Shield"));

        mockMvc.perform(get("/devices/shield/route").param("source", "jellyfin").param("item", ITEM_ID))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().string("no Jellyfin app is open on Shield"));
    }

    @Test
    void unknownDeviceSourceOrItemIs404() throws Exception {
        given(devices.device("ghost")).willReturn(Optional.empty());

        mockMvc.perform(post("/devices/ghost/play").param("source", "jellyfin").param("item", "x"))
                .andExpect(status().isNotFound())
                .andExpect(content().string("No device with id ghost"));

        given(devices.device("shield")).willReturn(Optional.of(shield));
        given(sources.find("nope")).willReturn(Optional.empty());

        mockMvc.perform(post("/devices/shield/play").param("source", "nope").param("item", "x"))
                .andExpect(status().isNotFound())
                .andExpect(content().string("No content source nope"));

        given(sources.find("jellyfin")).willReturn(Optional.of(jellyfin));
        given(jellyfin.item("missing")).willReturn(Optional.empty());

        mockMvc.perform(post("/devices/shield/play").param("source", "jellyfin").param("item", "missing"))
                .andExpect(status().isNotFound())
                .andExpect(content().string("No such item"));
    }

    @Test
    void failuresMapToStatusCodes() throws Exception {
        known();

        willThrow(new DeviceOfflineException("Shield is not connected")).given(playback).play(theItem, "shield");
        mockMvc.perform(post("/devices/shield/play").param("source", "jellyfin").param("item", ITEM_ID))
                .andExpect(status().isConflict())
                .andExpect(content().string("Shield is not connected"));

        willThrow(new UnroutableException("Shield: no route")).given(playback).play(theItem, "shield");
        mockMvc.perform(post("/devices/shield/play").param("source", "jellyfin").param("item", ITEM_ID))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().string("Shield: no route"));

        willThrow(new ActionFailedException("Jellyfin could not start playback on Shield")).given(playback).play(theItem, "shield");
        mockMvc.perform(post("/devices/shield/play").param("source", "jellyfin").param("item", ITEM_ID))
                .andExpect(status().isBadGateway())
                .andExpect(content().string("Jellyfin could not start playback on Shield"));

        willThrow(new ContentSourceException("Jellyfin is unreachable")).given(jellyfin).item(ITEM_ID);
        mockMvc.perform(post("/devices/shield/play").param("source", "jellyfin").param("item", ITEM_ID))
                .andExpect(status().isBadGateway())
                .andExpect(content().string("Jellyfin is unreachable"));
    }

    /** A race between the up-front existence check and playback (the device vanishes in between) still answers 404. */
    @Test
    void aDeviceThatDisappearsDuringPlaybackIs404() throws Exception {
        known();
        willThrow(new DeviceNotFoundException("No device with id shield")).given(playback).play(theItem, "shield");
        mockMvc.perform(post("/devices/shield/play").param("source", "jellyfin").param("item", ITEM_ID))
                .andExpect(status().isNotFound())
                .andExpect(content().string("No device with id shield"));
    }
}

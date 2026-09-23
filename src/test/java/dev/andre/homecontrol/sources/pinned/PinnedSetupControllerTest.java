package dev.andre.homecontrol.sources.pinned;

import dev.andre.homecontrol.adapters.androidtv.PairingService;
import dev.andre.homecontrol.core.playback.ContentKind;
import dev.andre.homecontrol.device.DeviceManager;
import dev.andre.homecontrol.storage.StorageException;
import dev.andre.homecontrol.web.SetupController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({PinnedSetupController.class, SetupController.class, PinnedSetupAdvice.class})
class PinnedSetupControllerTest {

    @TestConfiguration
    static class Config {
        @Bean
        PinnedProperties pinnedProperties() {
            return new PinnedProperties(true, 200);
        }
    }

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    PinnedShortcuts pins;

    @MockitoBean
    PairingService pairing;

    @MockitoBean
    DeviceManager devices;

    @BeforeEach
    void defaults() {
        given(devices.devices()).willReturn(List.of());
        given(devices.pairable()).willReturn(List.of());
        given(devices.addable()).willReturn(List.of());
        given(pins.all()).willReturn(List.of());
    }

    private Pin pin(String id, String title) {
        return new Pin(id, URI.create("https://example.org/" + id), "web", title, "example.org", null,
                ContentKind.VIDEO, null, Instant.parse("2026-09-16T10:00:00Z"));
    }

    @Test
    void addingRedirectsWithAMessage() throws Exception {
        given(pins.add("https://example.org/x", "Example")).willReturn(pin("p-aaaaaaaaaaaa", "Example"));

        mockMvc.perform(post("/setup/sources/pinned").param("url", "https://example.org/x").param("title", "Example"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/setup#pinned"))
                .andExpect(flash().attribute("pinnedMessage", "Pinned Example"));

        verify(pins).add("https://example.org/x", "Example");
    }

    @Test
    void errorsBecomeFlashErrors() throws Exception {
        willThrow(new IllegalArgumentException("That link is already pinned")).given(pins).add(any(), any());

        mockMvc.perform(post("/setup/sources/pinned").param("url", "https://example.org/x").param("title", "x"))
                .andExpect(flash().attribute("pinnedError", "That link is already pinned"));
    }

    @Test
    void renameMoveRemove() throws Exception {
        mockMvc.perform(post("/setup/sources/pinned/p-aaaaaaaaaaaa/title").param("title", "New title"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/setup#pinned"))
                .andExpect(flash().attribute("pinnedMessage", "Renamed to New title"));
        verify(pins).rename("p-aaaaaaaaaaaa", "New title");

        mockMvc.perform(post("/setup/sources/pinned/p-aaaaaaaaaaaa/move").param("direction", "down"))
                .andExpect(flash().attribute("pinnedMessage", "Order saved"));
        verify(pins).move("p-aaaaaaaaaaaa", false);

        mockMvc.perform(post("/setup/sources/pinned/p-aaaaaaaaaaaa/move").param("direction", "sideways"))
                .andExpect(flash().attribute("pinnedError", "Choose up or down"));
        verify(pins, never()).move(eq("p-aaaaaaaaaaaa"), eq(true));

        given(pins.find("p-aaaaaaaaaaaa")).willReturn(Optional.of(pin("p-aaaaaaaaaaaa", "New title")));
        mockMvc.perform(post("/setup/sources/pinned/p-aaaaaaaaaaaa/remove"))
                .andExpect(flash().attribute("pinnedMessage", "Removed New title"));
        verify(pins).remove("p-aaaaaaaaaaaa");

        given(pins.find("p-000000000000")).willReturn(Optional.empty());
        mockMvc.perform(post("/setup/sources/pinned/p-000000000000/remove"))
                .andExpect(flash().attribute("pinnedError", "No pinned link p-000000000000"));
    }

    @Test
    void storageFailuresBecomeFlashErrors() throws Exception {
        willThrow(new StorageException("disk full", null)).given(pins).add(any(), any());

        mockMvc.perform(post("/setup/sources/pinned").param("url", "https://example.org/x").param("title", "x"))
                .andExpect(flash().attribute("pinnedError", "Could not save pinned links: disk full"));
    }

    @Test
    void theSetupPageListsPins() throws Exception {
        given(pins.all()).willReturn(List.of(pin("p-aaaaaaaaaaaa", "A title"), pin("p-bbbbbbbbbbbb", "B title")));

        mockMvc.perform(get("/setup"))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("id=\"pinned\""),
                        containsString("A title"),
                        containsString("B title"),
                        containsString("https://example.org/p-aaaaaaaaaaaa"),
                        containsString("action=\"/setup/sources/pinned/p-aaaaaaaaaaaa/move\""),
                        containsString("name=\"url\""),
                        containsString("type=\"url\""))));

        given(pins.all()).willReturn(List.of());
        mockMvc.perform(get("/setup"))
                .andExpect(content().string(containsString("No pinned links yet.")));
    }
}

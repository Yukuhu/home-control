package dev.andre.homecontrol.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.file.Files;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class StaticAssetsTest {

    @DynamicPropertySource
    static void isolatedDataDirectory(DynamicPropertyRegistry registry) throws IOException {
        // A directory of its own, so a stale devices.json or keystore left by another run
        // never starts a session while this test is only checking static assets.
        String dataDir = Files.createTempDirectory("static-assets-test").toString();
        registry.add("shield.data-dir", () -> dataDir);
    }

    @Autowired
    MockMvc mockMvc;

    @Test
    void servesTheEsModulesTheDashboardLoads() throws Exception {
        mockMvc.perform(get("/js/app.js")).andExpect(status().isOk())
                .andExpect(content().string(containsString("import")));
        mockMvc.perform(get("/js/state-view.js")).andExpect(status().isOk())
                .andExpect(content().string(containsString("export function applyState")))
                .andExpect(content().string(containsString("nowPlaying")))
                .andExpect(content().string(containsString("volume-${deviceId}")))
                .andExpect(content().string(containsString("homecontrol:state")));
        mockMvc.perform(get("/js/remote-transport.js")).andExpect(status().isOk())
                .andExpect(content().string(containsString("export function sendKey")));
        mockMvc.perform(get("/js/events.js")).andExpect(status().isOk())
                .andExpect(content().string(containsString("export function on")));
        mockMvc.perform(get("/js/rails.js")).andExpect(status().isOk())
                .andExpect(content().string(containsString("export function watchRails")));
        mockMvc.perform(get("/js/play-sheet.js")).andExpect(status().isOk())
                .andExpect(content().string(containsString("export function openPlaySheet")))
                .andExpect(content().string(containsString("route-preview")));
        mockMvc.perform(get("/js/toast.js")).andExpect(status().isOk())
                .andExpect(content().string(containsString("export function toast")));
        mockMvc.perform(get("/app.js")).andExpect(status().isNotFound());
    }

    @Test
    void servesTheTouchpadAndPwaModulesAndTheOfflinePage() throws Exception {
        mockMvc.perform(get("/js/touchpad-gestures.js")).andExpect(status().isOk())
                .andExpect(content().string(containsString("export const TAP_SLOP_PX = 12")))
                .andExpect(content().string(containsString("export const SWIPE_THRESHOLD_PX = 24")))
                .andExpect(content().string(containsString("export const STEP_PX = 56")))
                .andExpect(content().string(containsString("export const MAX_STEPS = 4")))
                .andExpect(content().string(containsString("export const HOLD_MS = 450")));
        mockMvc.perform(get("/js/touchpad.js")).andExpect(status().isOk())
                .andExpect(content().string(containsString("homecontrol.remote.mode.v1")));
        mockMvc.perform(get("/js/pwa.js")).andExpect(status().isOk())
                .andExpect(content().string(containsString("beforeinstallprompt")))
                .andExpect(content().string(containsString("location.protocol === \"https:\"")));
        mockMvc.perform(get("/offline.html")).andExpect(status().isOk());
    }

    @Test
    void thePlaySheetScriptHandlesThePinForm() throws Exception {
        mockMvc.perform(get("/js/play-sheet.js")).andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("sheet-pin"),
                        containsString("/setup/sources/pinned/upgrade"),
                        not(containsString("not this title")))));
    }
}

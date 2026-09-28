package dev.andre.homecontrol.web;

import dev.andre.homecontrol.testsupport.FullAppTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class StaticAssetsTest extends FullAppTest {

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
        mockMvc.perform(get("/js/workflows.js")).andExpect(status().isOk())
                .andExpect(content().string(allOf(containsString("template.content.cloneNode(true)"), containsString("reindex"))));
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
    void servesTheThemeSwitchTheCyberpunkThemeAndItsFonts() throws Exception {
        mockMvc.perform(get("/js/theme.js")).andExpect(status().isOk())
                .andExpect(content().string(allOf(containsString("homecontrol.theme.v1"),
                        containsString("data-theme-toggle"), containsString("aria-pressed"))));
        mockMvc.perform(get("/themes/cyberpunk.css")).andExpect(status().isOk())
                .andExpect(content().string(allOf(containsString(":root[data-theme=\"cyberpunk\"]"),
                        containsString("url(fonts/rajdhani-500.woff2)"), containsString("url(fonts/rajdhani-700.woff2)"))));
        for (String font : new String[] {"/themes/fonts/rajdhani-500.woff2", "/themes/fonts/rajdhani-700.woff2"}) {
            mockMvc.perform(get(font)).andExpect(status().isOk())
                    .andExpect(header().string("Content-Type", "font/woff2"));
        }
        mockMvc.perform(get("/themes/fonts/OFL.txt")).andExpect(status().isOk())
                .andExpect(content().string(containsString("SIL OPEN FONT LICENSE Version 1.1")));
    }

    @Test
    void browsersRevalidateStylesAndScriptsSoARedeployIsPickedUp() throws Exception {
        for (String path : new String[] {"/app.css", "/themes/cyberpunk.css", "/js/app.js", "/js/theme.js"}) {
            String lastModified = mockMvc.perform(get(path)).andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", "no-cache"))
                    .andReturn().getResponse().getHeader("Last-Modified");
            mockMvc.perform(get(path).header("If-Modified-Since", lastModified))
                    .andExpect(status().isNotModified());
        }
    }

    @Test
    void thePlaySheetScriptHandlesThePinForm() throws Exception {
        mockMvc.perform(get("/js/play-sheet.js")).andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("sheet-pin"),
                        containsString("/setup/sources/pinned/upgrade"),
                        containsString("Home Control cannot open this event directly"),
                        containsString("LIVE_EVENT"))));
    }
}

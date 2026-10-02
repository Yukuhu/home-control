package dev.andre.homecontrol.web;

import dev.andre.homecontrol.testsupport.FullAppTest;
import dev.andre.homecontrol.themes.ThemeCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class StaticAssetsTest extends FullAppTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ThemeCatalog themes;

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
    void servesTheRemoteDrawerModuleThatTheDashboardStarts() throws Exception {
        mockMvc.perform(get("/js/remote-drawer.js")).andExpect(status().isOk())
                .andExpect(content().string(containsString("export function initRemoteDrawer()")));
        mockMvc.perform(get("/js/app.js")).andExpect(status().isOk())
                .andExpect(content().string(containsString("import { initRemoteDrawer } from \"./remote-drawer.js\";")))
                .andExpect(content().string(containsString("initRemoteDrawer();")))
                .andExpect(content().string(not(containsString("remote-drawer\")"))));
    }

    @Test
    void servesTheThemeCatalogAndItsImmutablePresentationAssets() throws Exception {
        mockMvc.perform(get("/js/theme.js")).andExpect(status().isOk())
                .andExpect(content().string(containsString("homecontrol.theme.v1")));
        mockMvc.perform(get("/themes/catalog.json")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-cache"))
                .andExpect(jsonPath("$.defaultId").value("default"))
                .andExpect(jsonPath("$.themes[0].stylesheet").value(themes.require("default").stylesheet()));
        mockMvc.perform(get("/themes/catalog.js")).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/javascript"))
                .andExpect(header().string("Cache-Control", "no-cache"))
                .andExpect(content().string(containsString("homeControlThemes")));
        for (var theme : themes.themes()) {
            for (String path : theme.assets()) {
                var asset = themes.asset(path).orElseThrow();
                mockMvc.perform(get(path)).andExpect(status().isOk())
                        .andExpect(content().contentTypeCompatibleWith(asset.contentType()))
                        .andExpect(content().bytes(asset.bytes()))
                        .andExpect(header().string("Cache-Control", allOf(
                                containsString("public"), containsString("max-age=31536000"), containsString("immutable"))));
            }
        }
    }

    @Test
    void browsersRevalidateStylesAndScriptsSoARedeployIsPickedUp() throws Exception {
        for (String path : new String[] {"/app.css", "/js/app.js", "/js/theme.js"}) {
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

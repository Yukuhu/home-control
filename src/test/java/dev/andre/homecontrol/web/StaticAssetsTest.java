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

import static org.hamcrest.Matchers.containsString;
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
                .andExpect(content().string(containsString("volume-${deviceId}")));
        mockMvc.perform(get("/js/remote-transport.js")).andExpect(status().isOk())
                .andExpect(content().string(containsString("export function sendKey")));
        mockMvc.perform(get("/app.js")).andExpect(status().isNotFound());
    }
}

package dev.andre.homecontrol.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PwaController.class)
class PwaControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void servesTheManifest() throws Exception {
        mockMvc.perform(get("/manifest.webmanifest"))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentType())
                        .contains("application/manifest+json"))
                .andExpect(jsonPath("$.name").value("Home Control"))
                .andExpect(jsonPath("$.display").value("standalone"))
                .andExpect(jsonPath("$.start_url").value("/"))
                .andExpect(jsonPath("$.icons[3].purpose").value("maskable"))
                .andExpect(jsonPath("$.icons[1].sizes").value("192x192"));
    }

    /**
     * No {@code @WebMvcTest} controller serves {@code /sw.js} (it is a static resource), so this
     * checks the file straight off the classpath instead of through MockMvc.
     */
    @Test
    void theServiceWorkerOnlyHandlesNavigations() throws Exception {
        String content;
        try (InputStream in = getClass().getResourceAsStream("/static/sw.js")) {
            assertThat(in).isNotNull();
            content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(content).contains("request.mode !== \"navigate\"");
        assertThat(content).doesNotContain("/events");
        assertThat(content).doesNotContain("POST");
        assertThat(content).doesNotContain("cache.put");
    }
}

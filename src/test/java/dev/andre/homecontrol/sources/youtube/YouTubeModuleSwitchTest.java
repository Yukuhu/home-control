package dev.andre.homecontrol.sources.youtube;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code home-control.youtube.enabled=false}: a household without a Google account has no YouTube code wired up. */
@SpringBootTest(properties = "home-control.youtube.enabled=false")
@AutoConfigureMockMvc
class YouTubeModuleSwitchTest {

    @DynamicPropertySource
    static void isolatedDataDirectory(DynamicPropertyRegistry registry) throws IOException {
        String dataDir = Files.createTempDirectory("youtube-module-switch-test").toString();
        registry.add("shield.data-dir", () -> dataDir);
    }

    @Autowired
    ApplicationContext context;

    @Autowired
    MockMvc mockMvc;

    @Test
    void theModuleCanBeSwitchedOff() throws Exception {
        assertThat(context.getBeanNamesForType(GoogleOAuthClient.class)).isEmpty();
        assertThat(context.getBeanNamesForType(YouTubeSetupService.class)).isEmpty();
        assertThat(context.getBeanNamesForType(YouTubeSetupController.class)).isEmpty();
        assertThat(context.getBeanNamesForType(YouTubeSetupAdvice.class)).isEmpty();

        mockMvc.perform(get("/setup"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("YouTube"))));

        mockMvc.perform(post("/setup/sources/youtube/connect"))
                .andExpect(status().isNotFound());
    }
}

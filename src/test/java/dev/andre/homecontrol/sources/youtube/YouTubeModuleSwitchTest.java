package dev.andre.homecontrol.sources.youtube;

import dev.andre.homecontrol.core.playback.RefStrategy;
import dev.andre.homecontrol.core.playback.RouteStrategy;
import dev.andre.homecontrol.testsupport.ModulesOffTest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code home-control.youtube.enabled=false}: a household without a Google account has no YouTube code wired up. */
class YouTubeModuleSwitchTest extends ModulesOffTest {

    @Test
    void theModuleCanBeSwitchedOff() throws Exception {
        assertThat(context.getBeanNamesForType(GoogleOAuthClient.class)).isEmpty();
        assertThat(context.getBeanNamesForType(YouTubeSetupService.class)).isEmpty();
        assertThat(context.getBeanNamesForType(YouTubeSetupController.class)).isEmpty();
        assertThat(context.getBeanNamesForType(YouTubeSetupAdvice.class)).isEmpty();
        assertThat(context.getBeansOfType(RouteStrategy.class).values())
                .noneMatch(strategy -> strategy instanceof RefStrategy<?> ref && ref.type() == YouTubeLoungeRef.class);

        mockMvc.perform(get("/setup"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("YouTube"))));

        mockMvc.perform(post("/setup/sources/youtube/connect"))
                .andExpect(status().isNotFound());
    }
}

package dev.andre.homecontrol.sources.youtube;

import dev.andre.homecontrol.security.LoginRequiredException;
import dev.andre.homecontrol.security.PasswordRejectedException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.FlashMap;

import java.net.URI;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(YouTubeSetupController.class)
class YouTubeSetupControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    YouTubeSetupService setup;

    @Test
    void connectRedirectsWithTheCodeHint() throws Exception {
        mockMvc.perform(post("/setup/sources/youtube/connect")
                        .param("clientId", "123456789012-abc123def456.apps.googleusercontent.com")
                        .param("clientSecret", "GOCSPX-abc")
                        .param("loginPassword", "pw-1234567890")
                        .param("loginPasswordConfirmation", "pw-1234567890"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/setup#youtube"))
                .andExpect(flash().attribute("youtubeMessage", "Enter the code on your phone"));

        ArgumentCaptor<YouTubeSetupService.ConnectRequest> captor =
                ArgumentCaptor.forClass(YouTubeSetupService.ConnectRequest.class);
        verify(setup).connect(captor.capture(), any());
        assertThat(captor.getValue().clientId()).isEqualTo("123456789012-abc123def456.apps.googleusercontent.com");
        assertThat(captor.getValue().clientSecret()).isEqualTo("GOCSPX-abc");
        assertThat(captor.getValue().loginPassword()).isEqualTo("pw-1234567890");
        assertThat(captor.getValue().loginPasswordConfirmation()).isEqualTo("pw-1234567890");
    }

    @Test
    void aFailureKeepsOnlyTheClientId() throws Exception {
        willThrow(new YouTubeException(YouTubeException.Kind.INVALID_INPUT, "Enter the client secret"))
                .given(setup).connect(any(), any());

        FlashMap flashMap = mockMvc.perform(post("/setup/sources/youtube/connect")
                        .param("clientId", "123456789012-abc123def456.apps.googleusercontent.com")
                        .param("clientSecret", "secret-value")
                        .param("loginPassword", "pw-secret")
                        .param("loginPasswordConfirmation", "pw-secret"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/setup#youtube"))
                .andExpect(flash().attribute("youtubeError", "Enter the client secret"))
                .andReturn().getFlashMap();

        @SuppressWarnings("unchecked")
        Map<String, String> form = (Map<String, String>) flashMap.get("youtubeForm");
        assertThat(form).containsExactly(Map.entry("clientId", "123456789012-abc123def456.apps.googleusercontent.com"));
        assertThat(form.values()).doesNotContain("secret-value", "pw-secret");
    }

    @Test
    void passwordAndLoginProblemsAreShown() throws Exception {
        willThrow(new PasswordRejectedException("The two passwords do not match"))
                .given(setup).connect(any(), any());
        mockMvc.perform(post("/setup/sources/youtube/connect").param("clientId", "x"))
                .andExpect(flash().attribute("youtubeError", "The two passwords do not match"));

        willThrow(new LoginRequiredException()).given(setup).connect(any(), any());
        mockMvc.perform(post("/setup/sources/youtube/connect").param("clientId", "x"))
                .andExpect(flash().attribute("youtubeError", "Log in first"));
    }

    @Test
    void authorizeCancelTestDisconnect() throws Exception {
        mockMvc.perform(post("/setup/sources/youtube/authorize"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/setup#youtube"))
                .andExpect(flash().attribute("youtubeMessage", "Enter the code on your phone"));
        verify(setup).authorize();

        mockMvc.perform(post("/setup/sources/youtube/cancel"))
                .andExpect(flash().attribute("youtubeMessage", "Cancelled"));
        verify(setup).cancel();

        given(setup.check()).willReturn("Google accepted the saved authorization");
        mockMvc.perform(post("/setup/sources/youtube/test"))
                .andExpect(flash().attribute("youtubeMessage", "Google accepted the saved authorization"));

        mockMvc.perform(post("/setup/sources/youtube/disconnect"))
                .andExpect(flash().attribute("youtubeMessage", "YouTube disconnected"));
        verify(setup).disconnect();

        willThrow(new YouTubeException(YouTubeException.Kind.UNREACHABLE, "Could not reach Google")).given(setup).authorize();
        mockMvc.perform(post("/setup/sources/youtube/authorize"))
                .andExpect(flash().attribute("youtubeError", "Could not reach Google"));
    }

    @Test
    void theAuthorizationFragmentPollsWhilePending() throws Exception {
        given(setup.authorizationStatus()).willReturn(new YouTubeAuthorizationService.Status(
                YouTubeAuthorizationService.State.PENDING, "GQVQ-JKEC", URI.create("https://www.google.com/device"),
                Instant.parse("2026-09-16T10:30:00Z"), null));

        mockMvc.perform(get("/setup/sources/youtube/authorization"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("GQVQ-JKEC")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("https://www.google.com/device")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("hx-trigger=\"every 3s\"")))
                .andExpect(header().doesNotExist("HX-Refresh"));
    }

    @Test
    void theAuthorizationFragmentRefreshesOnceConnected() throws Exception {
        given(setup.authorizationStatus()).willReturn(new YouTubeAuthorizationService.Status(
                YouTubeAuthorizationService.State.CONNECTED, null, null, null, "YouTube connected"));

        mockMvc.perform(get("/setup/sources/youtube/authorization"))
                .andExpect(status().isOk())
                .andExpect(header().string("HX-Refresh", "true"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("hx-trigger"))));
    }
}

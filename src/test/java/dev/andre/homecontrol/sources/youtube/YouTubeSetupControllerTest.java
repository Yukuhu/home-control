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
import java.util.List;
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
    void loadChooseAndWatchLaterEndpoints() throws Exception {
        given(setup.loadPlaylists()).willReturn(List.of(
                new YouTubePlaylists.PlaylistSummary("PLa", "Kids science", 17),
                new YouTubePlaylists.PlaylistSummary("PLb", "Watch this evening", 2)));

        mockMvc.perform(post("/setup/sources/youtube/playlists/load"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/setup#youtube"))
                .andExpect(flash().attribute("youtubeMessage", "Found 2 playlists"));
        verify(setup).loadPlaylists();

        mockMvc.perform(post("/setup/sources/youtube/playlists").param("playlist", "A", "B"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("youtubeMessage", "Playlists saved"));
        verify(setup).choosePlaylists(List.of("A", "B"));

        mockMvc.perform(post("/setup/sources/youtube/playlists"))
                .andExpect(flash().attribute("youtubeMessage", "Playlists saved"));
        verify(setup).choosePlaylists(List.of());

        mockMvc.perform(post("/setup/sources/youtube/watch-later").param("enabled", "true"))
                .andExpect(flash().attribute("youtubeMessage", "Watch Later shown"));
        verify(setup).setWatchLater(true);

        willThrow(new YouTubeException(YouTubeException.Kind.INVALID_INPUT, "Choose at most 20 playlists"))
                .given(setup).choosePlaylists(any());
        mockMvc.perform(post("/setup/sources/youtube/playlists").param("playlist", "A"))
                .andExpect(flash().attribute("youtubeError", "Choose at most 20 playlists"));
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

    @Test
    void loungeEndpoint() throws Exception {
        given(setup.setLounge("kitchen", true)).willReturn("Kitchen");
        given(setup.setLounge("kitchen", false)).willReturn("Kitchen");

        mockMvc.perform(post("/setup/sources/youtube/lounge").param("device", "kitchen").param("enabled", "true"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/setup#youtube"))
                .andExpect(flash().attribute("youtubeMessage", "YouTube Cast switched on for Kitchen"));
        verify(setup).setLounge("kitchen", true);

        mockMvc.perform(post("/setup/sources/youtube/lounge").param("device", "kitchen").param("enabled", "false"))
                .andExpect(flash().attribute("youtubeMessage", "YouTube Cast switched off for Kitchen"));

        willThrow(new YouTubeException(YouTubeException.Kind.INVALID_INPUT, "Only Cast devices can use YouTube Cast"))
                .given(setup).setLounge("living", true);
        mockMvc.perform(post("/setup/sources/youtube/lounge").param("device", "living").param("enabled", "true"))
                .andExpect(redirectedUrl("/setup#youtube"))
                .andExpect(flash().attribute("youtubeError", "Only Cast devices can use YouTube Cast"));
    }
}

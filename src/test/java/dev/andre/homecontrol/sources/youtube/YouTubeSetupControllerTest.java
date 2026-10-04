package dev.andre.homecontrol.sources.youtube;

import dev.andre.homecontrol.core.content.ContentSourceException;
import dev.andre.homecontrol.security.LoginRequiredException;
import dev.andre.homecontrol.security.PasswordRejectedException;
import dev.andre.homecontrol.testsupport.WebSliceTest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class YouTubeSetupControllerTest extends WebSliceTest {

    @Autowired
    MockMvc mockMvc;

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
        verify(youTubeSetup).connect(captor.capture(), any());
        assertThat(captor.getValue().clientId()).isEqualTo("123456789012-abc123def456.apps.googleusercontent.com");
        assertThat(captor.getValue().clientSecret()).isEqualTo("GOCSPX-abc");
        assertThat(captor.getValue().loginPassword()).isEqualTo("pw-1234567890");
        assertThat(captor.getValue().loginPasswordConfirmation()).isEqualTo("pw-1234567890");
    }

    @Test
    void aFailureKeepsOnlyTheClientId() throws Exception {
        willThrow(new YouTubeException(ContentSourceException.Kind.INVALID_INPUT, "Enter the client secret"))
                .given(youTubeSetup).connect(any(), any());

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
                .given(youTubeSetup).connect(any(), any());
        mockMvc.perform(post("/setup/sources/youtube/connect").param("clientId", "x"))
                .andExpect(flash().attribute("youtubeError", "The two passwords do not match"));

        willThrow(new LoginRequiredException()).given(youTubeSetup).connect(any(), any());
        mockMvc.perform(post("/setup/sources/youtube/connect").param("clientId", "x"))
                .andExpect(flash().attribute("youtubeError", "Log in first"));
    }

    @Test
    void authorizeCancelTestDisconnect() throws Exception {
        mockMvc.perform(post("/setup/sources/youtube/authorize"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/setup#youtube"))
                .andExpect(flash().attribute("youtubeMessage", "Enter the code on your phone"));
        verify(youTubeSetup).authorize();

        mockMvc.perform(post("/setup/sources/youtube/cancel"))
                .andExpect(flash().attribute("youtubeMessage", "Cancelled"));
        verify(youTubeSetup).cancel();

        given(youTubeSetup.check()).willReturn("Google accepted the saved authorization");
        mockMvc.perform(post("/setup/sources/youtube/test"))
                .andExpect(flash().attribute("youtubeMessage", "Google accepted the saved authorization"));

        mockMvc.perform(post("/setup/sources/youtube/disconnect"))
                .andExpect(flash().attribute("youtubeMessage", "YouTube disconnected"));
        verify(youTubeSetup).disconnect();

        willThrow(new YouTubeException(ContentSourceException.Kind.UNREACHABLE, "Could not reach Google")).given(youTubeSetup).authorize();
        mockMvc.perform(post("/setup/sources/youtube/authorize"))
                .andExpect(flash().attribute("youtubeError", "Could not reach Google"));
    }

    @Test
    void theAuthorizationFragmentPollsWhilePending() throws Exception {
        given(youTubeSetup.authorizationStatus()).willReturn(new YouTubeAuthorizationService.Status(
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
        given(youTubeSetup.loadPlaylists()).willReturn(List.of(
                new YouTubePlaylists.PlaylistSummary("PLa", "Kids science", 17),
                new YouTubePlaylists.PlaylistSummary("PLb", "Watch this evening", 2)));

        mockMvc.perform(post("/setup/sources/youtube/playlists/load"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/setup#youtube"))
                .andExpect(flash().attribute("youtubeMessage", "Found 2 playlists"));
        verify(youTubeSetup).loadPlaylists();

        mockMvc.perform(post("/setup/sources/youtube/playlists").param("playlist", "A", "B"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("youtubeMessage", "Playlists saved"));
        verify(youTubeSetup).choosePlaylists(List.of("A", "B"));

        mockMvc.perform(post("/setup/sources/youtube/playlists"))
                .andExpect(flash().attribute("youtubeMessage", "Playlists saved"));
        verify(youTubeSetup).choosePlaylists(List.of());

        mockMvc.perform(post("/setup/sources/youtube/watch-later").param("enabled", "true"))
                .andExpect(flash().attribute("youtubeMessage", "Watch Later shown"));
        verify(youTubeSetup).setWatchLater(true);

        willThrow(new YouTubeException(ContentSourceException.Kind.INVALID_INPUT, "Choose at most 20 playlists"))
                .given(youTubeSetup).choosePlaylists(any());
        mockMvc.perform(post("/setup/sources/youtube/playlists").param("playlist", "A"))
                .andExpect(flash().attribute("youtubeError", "Choose at most 20 playlists"));
    }

    @Test
    void theAuthorizationFragmentRefreshesOnceConnected() throws Exception {
        given(youTubeSetup.authorizationStatus()).willReturn(new YouTubeAuthorizationService.Status(
                YouTubeAuthorizationService.State.CONNECTED, null, null, null, "YouTube connected"));

        mockMvc.perform(get("/setup/sources/youtube/authorization"))
                .andExpect(status().isOk())
                .andExpect(header().string("HX-Refresh", "true"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("hx-trigger"))));
    }

    @Test
    void loungeEndpoint() throws Exception {
        given(youTubeSetup.setLounge("kitchen", true)).willReturn("Kitchen");
        given(youTubeSetup.setLounge("kitchen", false)).willReturn("Kitchen");

        mockMvc.perform(post("/setup/sources/youtube/lounge").param("device", "kitchen").param("enabled", "true"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/setup#youtube"))
                .andExpect(flash().attribute("youtubeMessage", "YouTube Cast switched on for Kitchen"));
        verify(youTubeSetup).setLounge("kitchen", true);

        mockMvc.perform(post("/setup/sources/youtube/lounge").param("device", "kitchen").param("enabled", "false"))
                .andExpect(flash().attribute("youtubeMessage", "YouTube Cast switched off for Kitchen"));

        willThrow(new YouTubeException(ContentSourceException.Kind.INVALID_INPUT, "Only Cast devices can use YouTube Cast"))
                .given(youTubeSetup).setLounge("living", true);
        mockMvc.perform(post("/setup/sources/youtube/lounge").param("device", "living").param("enabled", "true"))
                .andExpect(redirectedUrl("/setup#youtube"))
                .andExpect(flash().attribute("youtubeError", "Only Cast devices can use YouTube Cast"));
    }

    @Test
    void aFailedBrowserConnectKeepsOnlyTheClientIdAndIsNeverCached() throws Exception {
        willThrow(new YouTubeException(ContentSourceException.Kind.INVALID_INPUT, "Google browser sign-in needs an HTTPS domain"))
                .given(youTubeSetup).connectBrowser(any(), any(), any());

        FlashMap flashMap = mockMvc.perform(post("/setup/sources/youtube/browser/connect")
                        .param("clientId", "web-client.apps.googleusercontent.com")
                        .param("clientSecret", "secret-value"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/setup#youtube"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(flash().attribute("youtubeError", "Google browser sign-in needs an HTTPS domain"))
                .andReturn().getFlashMap();

        assertThat(flashMap.get("youtubeForm")).isEqualTo(Map.of("clientId", "web-client.apps.googleusercontent.com"));
    }

    @Test
    void aFailedConnectWithoutAClientIdKeepsAnEmptyOne() throws Exception {
        willThrow(new PasswordRejectedException("The two passwords do not match"))
                .given(youTubeSetup).connectBrowser(any(), any(), any());
        mockMvc.perform(post("/setup/sources/youtube/browser/connect"))
                .andExpect(flash().attribute("youtubeError", "The two passwords do not match"))
                .andExpect(flash().attribute("youtubeForm", Map.of("clientId", "")));

        willThrow(new LoginRequiredException()).given(youTubeSetup).connect(any(), any());
        mockMvc.perform(post("/setup/sources/youtube/connect"))
                .andExpect(flash().attribute("youtubeError", "Log in first"))
                .andExpect(flash().attribute("youtubeForm", Map.of("clientId", "")));
    }

    @Test
    void aBrowserConnectLoggedOutShowsWhy() throws Exception {
        willThrow(new LoginRequiredException()).given(youTubeSetup).connectBrowser(any(), any(), any());

        mockMvc.perform(post("/setup/sources/youtube/browser/connect").param("clientId", "x"))
                .andExpect(redirectedUrl("/setup#youtube"))
                .andExpect(flash().attribute("youtubeError", "Log in first"));
    }

    @Test
    void aFailedBrowserAuthorizationIsShownAndNeverCached() throws Exception {
        willThrow(new YouTubeException(ContentSourceException.Kind.INVALID_INPUT, "Connect a client first"))
                .given(youTubeSetup).authorizeBrowser(any(), any());

        mockMvc.perform(post("/setup/sources/youtube/browser/authorize"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/setup#youtube"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(flash().attribute("youtubeError", "Connect a client first"));
    }

    @Test
    void aHeadRequestToTheCallbackNeverUsesTheGrant() throws Exception {
        mockMvc.perform(head("/setup/sources/youtube/callback").param("state", "s").param("code", "c"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/setup#youtube"))
                .andExpect(header().string("Cache-Control", "no-store"));

        verify(youTubeSetup, never()).completeBrowser(any(), any(), any(), any());
    }

    @Test
    void aCallbackThatDidNotConnectIsShownAsAnError() throws Exception {
        given(youTubeSetup.completeBrowser(any(), any(), any(), any())).willReturn(new YouTubeAuthorizationService.Status(
                YouTubeAuthorizationService.State.FAILED, null, null, null, "Google sign-in was cancelled"));

        mockMvc.perform(get("/setup/sources/youtube/callback").param("state", "s").param("error", "access_denied"))
                .andExpect(redirectedUrl("/setup#youtube"))
                .andExpect(flash().attribute("youtubeError", "Google sign-in was cancelled"));
    }

    @Test
    void failuresOfTheOtherActionsAreShown() throws Exception {
        willThrow(new YouTubeException(ContentSourceException.Kind.UNREACHABLE, "Could not cancel"))
                .given(youTubeSetup).cancel();
        mockMvc.perform(post("/setup/sources/youtube/cancel"))
                .andExpect(flash().attribute("youtubeError", "Could not cancel"));

        given(youTubeSetup.check()).willThrow(new YouTubeException(ContentSourceException.Kind.UNAUTHORIZED,
                "Google refused the saved authorization"));
        mockMvc.perform(post("/setup/sources/youtube/test"))
                .andExpect(flash().attribute("youtubeError", "Google refused the saved authorization"));

        willThrow(new YouTubeException(ContentSourceException.Kind.UNREACHABLE, "Could not reach Google"))
                .given(youTubeSetup).disconnect();
        mockMvc.perform(post("/setup/sources/youtube/disconnect"))
                .andExpect(flash().attribute("youtubeError", "Could not reach Google"));

        given(youTubeSetup.loadPlaylists()).willThrow(new YouTubeException(ContentSourceException.Kind.RATE_LIMITED,
                "The YouTube quota for today is used up"));
        mockMvc.perform(post("/setup/sources/youtube/playlists/load"))
                .andExpect(flash().attribute("youtubeError", "The YouTube quota for today is used up"));

        mockMvc.perform(post("/setup/sources/youtube/watch-later").param("enabled", "false"))
                .andExpect(flash().attribute("youtubeMessage", "Watch Later hidden"));
        willThrow(new YouTubeException(ContentSourceException.Kind.INVALID_INPUT, "Connect YouTube first"))
                .given(youTubeSetup).setWatchLater(true);
        mockMvc.perform(post("/setup/sources/youtube/watch-later").param("enabled", "true"))
                .andExpect(redirectedUrl("/setup#youtube"))
                .andExpect(flash().attribute("youtubeError", "Connect YouTube first"));
    }
}

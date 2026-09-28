package dev.andre.homecontrol.sources.jellyfin;

import dev.andre.homecontrol.testsupport.WebSliceTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.FlashMap;

import java.net.URI;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class JellyfinSetupControllerTest extends WebSliceTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void savesPlayerOnlyForAKnownAndroidTvAndRejectsUnknownPlayers() throws Exception {
        var shield = new dev.andre.homecontrol.core.Device("shield", "Shield", dev.andre.homecontrol.core.DeviceKind.ANDROID_TV,
                "10.0.0.5", Map.of("androidtv", Map.of()), java.time.Instant.EPOCH);
        given(devices.device("shield")).willReturn(java.util.Optional.of(shield));
        var settings = new JellyfinSettings(URI.create("http://nas:8096"), URI.create("http://nas:8096"),
                "server", "nas", "10.11.2", "user", "andre", JellyfinSettings.AuthMode.PASSWORD, "hc", "F007D354", Map.of());
        given(jellyfinSetup.settings()).willReturn(java.util.Optional.of(settings));
        mockMvc.perform(post("/setup/sources/jellyfin/players").param("device", "shield").param("player", "vlc"))
                .andExpect(redirectedUrl("/setup")).andExpect(flash().attribute("jellyfinMessage", "Player preference saved"));
        verify(jellyfinSetup).save(settings.withPlayer("shield", JellyfinSettings.Player.VLC));
        org.mockito.Mockito.clearInvocations(jellyfinSetup);
        mockMvc.perform(post("/setup/sources/jellyfin/players").param("device", "shield").param("player", "unknown"))
                .andExpect(flash().attributeExists("jellyfinError"));
        mockMvc.perform(post("/setup/sources/jellyfin/players").param("device", "missing").param("player", "vlc"))
                .andExpect(flash().attributeExists("jellyfinError"));
        verify(jellyfinSetup, never()).save(any());
    }

    @Test
    void aSuccessfulConnectRedirectsWithAMessage() throws Exception {
        JellyfinSettings connected = new JellyfinSettings(URI.create("http://nas:8096"), URI.create("http://nas:8096"),
                "server-1", "nas", "10.11.2", "user-1", "andre", JellyfinSettings.AuthMode.PASSWORD,
                "dev-1", "F007D354", Map.of());
        given(jellyfinSetup.connect(any(), any())).willReturn(connected);

        mockMvc.perform(post("/setup/sources/jellyfin")
                        .param("serverUrl", "http://nas:8096")
                        .param("mode", "password")
                        .param("userName", "andre")
                        .param("password", "user pw")
                        .param("loginPassword", "household pw 1")
                        .param("loginPasswordConfirmation", "household pw 1"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/setup"))
                .andExpect(flash().attribute("jellyfinMessage", "Connected to nas as andre"));

        org.mockito.ArgumentCaptor<JellyfinSetupService.ConnectRequest> captor =
                org.mockito.ArgumentCaptor.forClass(JellyfinSetupService.ConnectRequest.class);
        verify(jellyfinSetup).connect(captor.capture(), any());
        assertThat(captor.getValue().mode()).isEqualTo(JellyfinSettings.AuthMode.PASSWORD);

        mockMvc.perform(post("/setup/sources/jellyfin")
                        .param("serverUrl", "http://nas:8096")
                        .param("mode", "api-key")
                        .param("userName", "andre")
                        .param("apiKey", "key-1")
                        .param("loginPassword", "household pw 1")
                        .param("loginPasswordConfirmation", "household pw 1"));
        verify(jellyfinSetup, times(2)).connect(captor.capture(), any());
        assertThat(captor.getValue().mode()).isEqualTo(JellyfinSettings.AuthMode.API_KEY);
    }

    @Test
    void aFailureKeepsTheNonSecretFieldsButNeverThePasswordOrKey() throws Exception {
        willThrow(new JellyfinException(JellyfinException.Kind.UNAUTHORIZED, "Jellyfin rejected the user name or password"))
                .given(jellyfinSetup).connect(any(), any());

        FlashMap flashMap = mockMvc.perform(post("/setup/sources/jellyfin")
                        .param("serverUrl", "http://nas:8096")
                        .param("deviceServerUrl", "http://nas.lan:8096")
                        .param("mode", "password")
                        .param("userName", "andre")
                        .param("password", "pw-secret")
                        .param("apiKey", "key-secret")
                        .param("loginPassword", "household pw 1")
                        .param("loginPasswordConfirmation", "household pw 1"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/setup"))
                .andExpect(flash().attribute("jellyfinError", "Jellyfin rejected the user name or password"))
                .andExpect(flash().attributeExists("jellyfinForm"))
                .andReturn().getFlashMap();

        @SuppressWarnings("unchecked")
        Map<String, String> form = (Map<String, String>) flashMap.get("jellyfinForm");
        assertThat(form).containsEntry("serverUrl", "http://nas:8096")
                .containsEntry("deviceServerUrl", "http://nas.lan:8096")
                .containsEntry("mode", "password")
                .containsEntry("userName", "andre");
        assertThat(form.values()).doesNotContain("pw-secret", "key-secret");
    }

    @Test
    void testAndDisconnectReportTheirOutcome() throws Exception {
        given(jellyfinSetup.check()).willReturn("Connected to nas as andre");

        mockMvc.perform(post("/setup/sources/jellyfin/test"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/setup"))
                .andExpect(flash().attribute("jellyfinMessage", "Connected to nas as andre"));

        willThrow(new JellyfinException(JellyfinException.Kind.UNREACHABLE, "Could not reach Jellyfin"))
                .given(jellyfinSetup).check();

        mockMvc.perform(post("/setup/sources/jellyfin/test"))
                .andExpect(flash().attribute("jellyfinError", "Could not reach Jellyfin"));

        mockMvc.perform(post("/setup/sources/jellyfin/disconnect"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/setup"))
                .andExpect(flash().attribute("jellyfinMessage", "Jellyfin disconnected"));

        verify(jellyfinSetup).disconnect();
    }

    @Test
    void linksASessionToADevice() throws Exception {
        mockMvc.perform(post("/setup/sources/jellyfin/links")
                        .param("session", "jf-1")
                        .param("device", "shield"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/setup"));

        verify(jellyfinSetup).link("jf-1", "shield");
    }
}

package dev.andre.homecontrol.sources.tmdb;

import dev.andre.homecontrol.adapters.androidtv.PairingService;
import dev.andre.homecontrol.device.DeviceManager;
import dev.andre.homecontrol.security.LoginRequiredException;
import dev.andre.homecontrol.security.LoginService;
import dev.andre.homecontrol.security.PasswordRejectedException;
import dev.andre.homecontrol.web.SetupController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({TmdbSetupController.class, SetupController.class, TmdbSetupAdvice.class})
class TmdbSetupControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    TmdbSetupService setup;

    @MockitoBean
    PairingService pairing;

    @MockitoBean
    DeviceManager devices;

    @MockitoBean
    LoginService login;

    @BeforeEach
    void defaults() {
        given(devices.devices()).willReturn(List.of());
        given(devices.pairable()).willReturn(List.of());
        given(devices.addable()).willReturn(List.of());
        given(login.loginRequired()).willReturn(false);
        given(setup.settings()).willReturn(java.util.Optional.empty());
    }

    @Test
    void connectRedirectsWithAMessage() throws Exception {
        mockMvc.perform(post("/setup/sources/tmdb")
                        .param("credential", "x")
                        .param("loginPassword", "p")
                        .param("loginPasswordConfirmation", "p"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/setup#tmdb"))
                .andExpect(flash().attribute("tmdbMessage", "TMDB connected"));

        ArgumentCaptor<TmdbSetupService.ConnectRequest> captor = ArgumentCaptor.forClass(TmdbSetupService.ConnectRequest.class);
        verify(setup).connect(captor.capture(), any());
        assertThat(captor.getValue().credential()).isEqualTo("x");
        assertThat(captor.getValue().loginPassword()).isEqualTo("p");
        assertThat(captor.getValue().loginPasswordConfirmation()).isEqualTo("p");
    }

    @Test
    void failuresBecomeFlashErrors() throws Exception {
        willThrow(new TmdbException(TmdbException.Kind.UNAUTHORIZED, "TMDB rejected the API key or read access token"))
                .given(setup).connect(any(), any());

        mockMvc.perform(post("/setup/sources/tmdb").param("credential", "x"))
                .andExpect(flash().attribute("tmdbError", "TMDB rejected the API key or read access token"));

        willThrow(new PasswordRejectedException("The two passwords do not match"))
                .given(setup).connect(any(), any());

        mockMvc.perform(post("/setup/sources/tmdb").param("credential", "x"))
                .andExpect(flash().attribute("tmdbError", "The two passwords do not match"));

        willThrow(new LoginRequiredException()).given(setup).connect(any(), any());

        mockMvc.perform(post("/setup/sources/tmdb").param("credential", "x"))
                .andExpect(flash().attribute("tmdbError", "Log in again to change TMDB"));
    }

    @Test
    void testAndDisconnect() throws Exception {
        given(setup.check()).willReturn("TMDB accepted the read access token");

        mockMvc.perform(post("/setup/sources/tmdb/test"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/setup#tmdb"))
                .andExpect(flash().attribute("tmdbMessage", "TMDB accepted the read access token"));

        mockMvc.perform(post("/setup/sources/tmdb/disconnect"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/setup#tmdb"))
                .andExpect(flash().attribute("tmdbMessage", "TMDB disconnected"));
        verify(setup).disconnect();
    }

    @Test
    void theSetupPageShowsTheSection() throws Exception {
        mockMvc.perform(get("/setup"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("id=\"tmdb\""),
                        org.hamcrest.Matchers.containsString("name=\"credential\""),
                        org.hamcrest.Matchers.containsString("type=\"password\""),
                        org.hamcrest.Matchers.containsString("loginPassword"),
                        org.hamcrest.Matchers.containsString(
                                "This product uses the TMDB API but is not endorsed or certified by TMDB."))));

        given(setup.settings()).willReturn(java.util.Optional.of(new TmdbSettings(TmdbCredential.Kind.BEARER,
                java.time.Instant.parse("2026-09-16T10:00:00Z"))));
        given(login.loginRequired()).willReturn(true);

        String body = mockMvc.perform(get("/setup")).andReturn().getResponse().getContentAsString();
        assertThat(body).contains("Connected (read access token)").contains("Disconnect");
        assertThat(body).doesNotContain("loginPassword");
        assertThat(body).doesNotContain(FakeTmdbServer.READ_TOKEN).doesNotContain(FakeTmdbServer.API_KEY);
    }
}

package dev.andre.homecontrol.sources.tmdb;

import dev.andre.homecontrol.security.LoginRequiredException;
import dev.andre.homecontrol.security.PasswordRejectedException;
import dev.andre.homecontrol.testsupport.WebSliceTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TmdbSetupControllerTest extends WebSliceTest {

    @Autowired
    MockMvc mockMvc;

    @BeforeEach
    void defaults() {
        given(devices.devices()).willReturn(List.of());
        given(devices.pairable()).willReturn(List.of());
        given(devices.addable()).willReturn(List.of());
        given(login.loginRequired()).willReturn(false);
        given(tmdbSetup.settings()).willReturn(java.util.Optional.empty());
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
        verify(tmdbSetup).connect(captor.capture(), any());
        assertThat(captor.getValue().credential()).isEqualTo("x");
        assertThat(captor.getValue().loginPassword()).isEqualTo("p");
        assertThat(captor.getValue().loginPasswordConfirmation()).isEqualTo("p");
    }

    @Test
    void failuresBecomeFlashErrors() throws Exception {
        willThrow(new TmdbException(TmdbException.Kind.UNAUTHORIZED, "TMDB rejected the API key or read access token"))
                .given(tmdbSetup).connect(any(), any());

        mockMvc.perform(post("/setup/sources/tmdb").param("credential", "x"))
                .andExpect(flash().attribute("tmdbError", "TMDB rejected the API key or read access token"));

        willThrow(new PasswordRejectedException("The two passwords do not match"))
                .given(tmdbSetup).connect(any(), any());

        mockMvc.perform(post("/setup/sources/tmdb").param("credential", "x"))
                .andExpect(flash().attribute("tmdbError", "The two passwords do not match"));

        willThrow(new LoginRequiredException()).given(tmdbSetup).connect(any(), any());

        mockMvc.perform(post("/setup/sources/tmdb").param("credential", "x"))
                .andExpect(flash().attribute("tmdbError", "Log in again to change TMDB"));
    }

    @Test
    void testAndDisconnect() throws Exception {
        given(tmdbSetup.check()).willReturn("TMDB accepted the read access token");

        mockMvc.perform(post("/setup/sources/tmdb/test"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/setup#tmdb"))
                .andExpect(flash().attribute("tmdbMessage", "TMDB accepted the read access token"));

        mockMvc.perform(post("/setup/sources/tmdb/disconnect"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/setup#tmdb"))
                .andExpect(flash().attribute("tmdbMessage", "TMDB disconnected"));
        verify(tmdbSetup).disconnect();
    }

    /** The TMDB section of the setup page, which the shared web slice renders among every other module's section. */
    private static String tmdbSection(String page) {
        int start = page.indexOf("id=\"tmdb\"");
        assertThat(start).as("the TMDB section").isNotNegative();
        return page.substring(start, page.indexOf("</section>", start));
    }

    @Test
    void theSetupPageShowsTheSection() throws Exception {
        String page = mockMvc.perform(get("/setup"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(tmdbSection(page)).contains("name=\"credential\"", "type=\"password\"", "loginPassword",
                "This product uses the TMDB API but is not endorsed or certified by TMDB.");

        given(tmdbSetup.settings()).willReturn(java.util.Optional.of(new TmdbSettings(TmdbCredential.Kind.BEARER,
                java.time.Instant.parse("2026-09-16T10:00:00Z"))));
        given(login.loginRequired()).willReturn(true);

        String body = mockMvc.perform(get("/setup")).andReturn().getResponse().getContentAsString();
        assertThat(tmdbSection(body)).contains("Connected (read access token)").contains("Disconnect");
        assertThat(body).doesNotContain("loginPassword")
                .doesNotContain(FakeTmdbServer.READ_TOKEN).doesNotContain(FakeTmdbServer.API_KEY);
    }
}

package dev.andre.homecontrol.web;

import dev.andre.homecontrol.security.LoginService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class LoginGatingTest {

    static final String PASSWORD = "household password";
    static Path dataDir;

    @DynamicPropertySource
    static void isolatedDataDirectory(DynamicPropertyRegistry registry) throws IOException {
        dataDir = Files.createTempDirectory("login-gating");
        registry.add("shield.data-dir", dataDir::toString);
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    LoginService login;

    /** Each method gets a new context (fresh rate limiter), so the files must go too. */
    @AfterEach
    void deleteSecrets() throws IOException {
        Files.deleteIfExists(dataDir.resolve("secrets.json"));
        Files.deleteIfExists(dataDir.resolve("secret.key"));
    }

    private void storeAFirstSecret() {
        login.storeSecrets(Map.of("jellyfin.token", "0123456789abcdef"), PASSWORD, PASSWORD, new MockHttpServletRequest());
    }

    private MockHttpSession loggedIn() throws Exception {
        MvcResult result = mockMvc.perform(post("/login").header("Host", "localhost").header("Origin", "http://localhost")
                        .param("password", PASSWORD).param("next", "/setup"))
                .andExpect(status().isFound()).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    @Test
    void aDeviceOnlyDeploymentIsUnchanged() throws Exception {
        mockMvc.perform(get("/setup").accept("text/html"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Login password"))))
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(header().doesNotExist("X-Frame-Options"));
        mockMvc.perform(post("/devices/nope/key/HOME").header("Host", "localhost").header("Origin", "http://localhost"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/devices/nope/key/HOME")).andExpect(status().isNotFound());
        mockMvc.perform(get("/login")).andExpect(redirectedUrl("/"));
        try (var files = Files.list(dataDir)) {
            assertThat(files.map(p -> p.getFileName().toString())).doesNotContain("secrets.json", "secret.key");
        }
    }

    @Test
    void crossOriginRequestsAreRefusedBeforeAnyLoginExists() throws Exception {
        for (String path : new String[] {"/devices/nope/key/HOME", "/devices;x/nope/key/HOME", "/%64evices/nope/key/HOME",
                "/setup/forget", "/setup;x/forget", "/login", "/logout", "/setup/password"}) {
            mockMvc.perform(post(URI.create(path)).header("Sec-Fetch-Site", "cross-site"))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post(URI.create(path)).header("Host", "localhost").header("Origin", "http://evil.example"))
                    .andExpect(status().isForbidden());
        }
        mockMvc.perform(delete(URI.create("/devices;x/nope")).header("Sec-Fetch-Site", "cross-site"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/devices/nope/key/HOME").header("Sec-Fetch-Site", "same-origin"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/setup").header("Sec-Fetch-Site", "cross-site").accept("text/html"))
                .andExpect(status().isOk());
    }

    @Test
    void onceASecretExistsEveryPathButTheLoginPageIsGated() throws Exception {
        storeAFirstSecret();

        mockMvc.perform(get("/setup").accept("text/html")).andExpect(redirectedUrl("/login?next=%2Fsetup"));
        mockMvc.perform(get("/events")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/vendor/htmx.min.js")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/devices/nope/key/HOME").header("HX-Request", "true"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("HX-Redirect", "/login"));
        mockMvc.perform(post(URI.create("/devices;x/nope/key/HOME"))).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/app.css")).andExpect(status().isOk());
        mockMvc.perform(get("/login")).andExpect(status().isOk())
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(content().string(containsString("name=\"password\"")));
    }

    @Test
    void theRightPasswordOpensTheAppAndTheWrongOneDoesNot() throws Exception {
        storeAFirstSecret();

        mockMvc.perform(post("/login").param("password", "wrong password"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(containsString("Wrong password")))
                .andExpect(content().string(not(containsString("wrong password"))));
        MockHttpSession session = loggedIn();

        mockMvc.perform(get("/setup").session(session).accept("text/html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Login password")))
                .andExpect(content().string(not(containsString("0123456789abcdef"))))
                .andExpect(content().string(not(containsString("argon2id"))));
        mockMvc.perform(post("/logout").session(session)).andExpect(redirectedUrl("/login"));
        mockMvc.perform(get("/setup").session(session).accept("text/html")).andExpect(status().isFound());
    }

    @Test
    void theNextParameterCannotRedirectOffSite() throws Exception {
        storeAFirstSecret();

        mockMvc.perform(post("/login").param("password", PASSWORD).param("next", "//evil.example/x"))
                .andExpect(redirectedUrl("/"));
    }

    @Test
    void crossOriginPostsAreRefusedOnceALoginExists() throws Exception {
        storeAFirstSecret();
        MockHttpSession session = loggedIn();

        mockMvc.perform(post("/devices/nope/key/HOME").session(session)
                        .header("Host", "localhost").header("Origin", "http://evil.example"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(URI.create("/devices;x/nope/key/HOME")).session(session)
                        .header("Sec-Fetch-Site", "cross-site"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/login").header("Host", "localhost").header("Origin", "http://evil.example")
                        .param("password", PASSWORD))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/devices/nope/key/HOME").session(session).header("Sec-Fetch-Site", "same-origin"))
                .andExpect(status().isNotFound());
    }

    @Test
    void repeatedFailuresAreRateLimitedEvenForTheRightPassword() throws Exception {
        storeAFirstSecret();
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/login").param("password", "wrong " + i)).andExpect(status().isUnauthorized());
        }

        mockMvc.perform(post("/login").param("password", PASSWORD))
                .andExpect(status().isTooManyRequests())
                .andExpect(content().string(containsString("Too many attempts")));
    }

    @Test
    void changingThePasswordLogsOutOtherBrowsers() throws Exception {
        storeAFirstSecret();
        MockHttpSession phone = loggedIn();
        MockHttpSession laptop = loggedIn();

        mockMvc.perform(post("/setup/password").session(laptop)
                        .param("current", PASSWORD).param("password", "a new household password")
                        .param("confirmation", "a new household password"))
                .andExpect(redirectedUrl("/setup"));

        mockMvc.perform(get("/setup").session(phone).accept("text/html")).andExpect(status().isFound());
        mockMvc.perform(get("/setup").session(laptop).accept("text/html")).andExpect(status().isOk());
    }

    @Test
    void aRebindingHostIsMisdirectedBeforeAndAfterLogin() throws Exception {
        mockMvc.perform(get("/setup").header("Host", "evil.example").accept("text/html"))
                .andExpect(status().is(421))
                .andExpect(content().string(not(containsString("evil"))));
        mockMvc.perform(post("/login").header("Host", "evil.example:8080").header("Origin", "http://evil.example:8080")
                        .header("Sec-Fetch-Site", "same-origin").param("password", PASSWORD))
                .andExpect(status().is(421));

        storeAFirstSecret();
        MockHttpSession session = loggedIn();
        mockMvc.perform(get("/setup").session(session).header("Host", "evil.example").accept("text/html"))
                .andExpect(status().is(421));
        mockMvc.perform(get("/setup").session(session).header("Host", "[::1]:8080").accept("text/html"))
                .andExpect(status().isOk());
    }

    @Test
    void guessingTheCurrentPasswordOnTheSetupPageIsRateLimitedToo() throws Exception {
        storeAFirstSecret();
        MockHttpSession session = loggedIn();
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/setup/password").session(session)
                            .param("current", "wrong " + i).param("password", "a new household password")
                            .param("confirmation", "a new household password"))
                    .andExpect(redirectedUrl("/setup"))
                    .andExpect(flash().attribute("loginError", "The current password is wrong"));
        }

        mockMvc.perform(post("/setup/password").session(session)
                        .param("current", PASSWORD).param("password", "a new household password")
                        .param("confirmation", "a new household password"))
                .andExpect(redirectedUrl("/setup"))
                .andExpect(flash().attribute("loginError", containsString("Too many attempts")));
        mockMvc.perform(post("/login").param("password", PASSWORD)).andExpect(status().isTooManyRequests());
        assertThat(login.isAuthenticated(session)).isTrue();
    }

    @Test
    void manifestAndIconsStayOpenWhenALoginIsRequired() throws Exception {
        storeAFirstSecret();

        mockMvc.perform(get("/manifest.webmanifest")).andExpect(status().isOk());
        mockMvc.perform(get("/icons/icon-192.png")).andExpect(status().isOk());
        mockMvc.perform(get("/icons/icon.svg")).andExpect(status().isOk());
        mockMvc.perform(get("/offline.html")).andExpect(status().isOk());

        mockMvc.perform(get("/sw.js")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/js/touchpad.js")).andExpect(status().isUnauthorized());
    }

    /**
     * The open list is exact raw-URI matches, never a prefix — {@code OPEN_PREFIXES} was the
     * bug C1 already fixed once (a {@code ;param}/percent-encoding segment can make a container
     * normalise a traversal back onto an allowed prefix while the raw URI still starts with it).
     * Re-adding a prefix for the PWA assets would reopen exactly that hole.
     */
    @Test
    void iconLookalikePathsStayGatedEvenThoughIconsAreOpen() throws Exception {
        storeAFirstSecret();

        mockMvc.perform(get(URI.create("/icons/..;/setup"))).andExpect(status().isUnauthorized());
        mockMvc.perform(get(URI.create("/icons/icon-192.png;x"))).andExpect(status().isUnauthorized());
        mockMvc.perform(get(URI.create("/icons/icon.svg;x"))).andExpect(status().isUnauthorized());
        mockMvc.perform(get(URI.create("/icons/other.png"))).andExpect(status().isUnauthorized());
    }

    @Test
    void aRejectedNewPasswordIsNotAGuess() throws Exception {
        storeAFirstSecret();
        MockHttpSession session = loggedIn();
        for (int i = 0; i < 6; i++) {
            mockMvc.perform(post("/setup/password").session(session)
                            .param("current", PASSWORD).param("password", "short").param("confirmation", "short"))
                    .andExpect(flash().attribute("loginError", "The login password needs at least 10 characters"));
        }

        mockMvc.perform(post("/login").param("password", PASSWORD)).andExpect(status().isFound());
    }
}

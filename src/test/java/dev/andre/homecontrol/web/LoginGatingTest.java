package dev.andre.homecontrol.web;

import dev.andre.homecontrol.security.LoginService;
import dev.andre.homecontrol.security.RememberedLogins;
import dev.andre.homecontrol.security.RequestLoginContext;
import dev.andre.homecontrol.storage.DataDirectory;
import dev.andre.homecontrol.testsupport.FullAppReset;
import dev.andre.homecontrol.testsupport.FullAppTest;
import dev.andre.homecontrol.themes.ThemeCatalog;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.net.URI;
import java.nio.file.Files;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LoginGatingTest extends FullAppTest {

    static final String PASSWORD = "household password";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    LoginService login;

    @Autowired
    ThemeCatalog themes;

    @Autowired
    ApplicationContext context;

    @Autowired
    DataDirectory data;

    /** The context is shared, so every test starts from a fresh install: no login, no secrets, no rate limit. */
    @AfterEach
    void freshInstall() {
        FullAppReset.reset(context);
    }

    private void storeAFirstSecret() {
        login.storeSecrets(Map.of("jellyfin.token", "0123456789abcdef"), PASSWORD, PASSWORD, new RequestLoginContext(new MockHttpServletRequest(), login));
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
                .andExpect(content().string(not(containsString("Current password"))))
                .andExpect(header().doesNotExist("Set-Cookie"))
                // No login, but the page still cannot be framed.
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Content-Security-Policy", containsString("frame-ancestors 'none'")));
        mockMvc.perform(post("/devices/nope/key/HOME").header("Host", "localhost").header("Origin", "http://localhost"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/devices/nope/key/HOME")).andExpect(status().isNotFound());
        mockMvc.perform(get("/login")).andExpect(redirectedUrl("/"));
        assertThat(login.loginRequired()).isFalse();
    }

    @Test
    void crossOriginRequestsAreRefusedBeforeAnyLoginExists() throws Exception {
        for (String path : new String[] {"/devices/nope/key/HOME", "/devices;x/nope/key/HOME", "/%64evices/nope/key/HOME",
                "/setup/forget", "/setup;x/forget", "/login", "/logout", "/setup/password", "/setup/password/set",
                "/setup/password/remove"}) {
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

        mockMvc.perform(get("/setup").accept("text/html")).andExpect(redirectedUrl("/login?next=%2Fsetup"))
                .andExpect(header().string("Content-Security-Policy", containsString("script-src 'self';")));
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

    /** Deleting secrets.json would also delete the TV pairings and the keystore's password: never advised. */
    @Test
    void aForgottenPasswordIsResetWithTheSettingThatKeepsTheTvPairings() throws Exception {
        storeAFirstSecret();

        mockMvc.perform(get("/login")).andExpect(status().isOk())
                .andExpect(content().string(containsString("<code>HOME_CONTROL_RESET_LOGIN=true</code>")))
                .andExpect(content().string(containsString("docs/user/security.md")))
                .andExpect(content().string(not(containsString("delete <code>secrets.json</code>"))));
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
                .andExpect(content().string(containsString("Current password")))
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

    /** Logs in and returns the remembered-login cookie, which is all a browser has left after a restart. */
    private Cookie rememberedLogin() throws Exception {
        MvcResult result = mockMvc.perform(post("/login").header("Host", "localhost").header("Origin", "http://localhost")
                        .param("password", PASSWORD).param("next", "/setup"))
                .andExpect(status().isFound()).andReturn();
        Cookie cookie = result.getResponse().getCookie("HOME_CONTROL_LOGIN");
        assertThat(cookie).isNotNull();
        return cookie;
    }

    @Test
    void aLoginSurvivesARestartOfTheServer() throws Exception {
        storeAFirstSecret();
        Cookie remembered = rememberedLogin();

        MvcResult result = mockMvc.perform(get("/setup").cookie(remembered).accept("text/html"))
                .andExpect(status().isOk()).andReturn();
        MockHttpSession resumed = (MockHttpSession) result.getRequest().getSession(false);

        mockMvc.perform(get("/setup").session(resumed).accept("text/html")).andExpect(status().isOk());
        mockMvc.perform(get("/events").cookie(remembered).accept("text/event-stream")).andExpect(status().isOk());
    }

    @Test
    void aBrowserThatLoggedOutIsNotLetBackInByItsOldCookie() throws Exception {
        storeAFirstSecret();
        MvcResult loggedIn = mockMvc.perform(post("/login").header("Host", "localhost")
                        .header("Origin", "http://localhost").param("password", PASSWORD))
                .andExpect(status().isFound()).andReturn();
        Cookie remembered = loggedIn.getResponse().getCookie("HOME_CONTROL_LOGIN");

        mockMvc.perform(post("/logout").header("Host", "localhost").header("Origin", "http://localhost")
                        .session((MockHttpSession) loggedIn.getRequest().getSession(false)).cookie(remembered))
                .andExpect(status().isFound())
                .andExpect(cookie().maxAge("HOME_CONTROL_LOGIN", 0));

        mockMvc.perform(get("/setup").cookie(remembered).accept("text/html"))
                .andExpect(redirectedUrl("/login?next=%2Fsetup"));
    }

    @Test
    void aDamagedLoginsFileMeansLoggingInAgainNotAnError() throws Exception {
        storeAFirstSecret();
        Cookie remembered = rememberedLogin();
        // As after a restart: nothing in memory, and the file is damaged.
        context.getBean(RememberedLogins.class).clear();
        Files.writeString(data.resolve(DataDirectory.LOGINS), "{ not json");

        mockMvc.perform(get("/setup").cookie(remembered).accept("text/html"))
                .andExpect(redirectedUrl("/login?next=%2Fsetup"));
        mockMvc.perform(get("/events").cookie(remembered).accept("text/event-stream"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aFormPostedWithoutALoginGoesToTheLoginPageAndBackToItsPage() throws Exception {
        storeAFirstSecret();

        mockMvc.perform(post("/setup/password").header("Host", "localhost").header("Origin", "http://localhost")
                        .header("Referer", "http://localhost/setup?tab=account").accept("text/html")
                        .param("current", "x"))
                .andExpect(status().isSeeOther())
                .andExpect(redirectedUrl("/login?next=%2Fsetup%3Ftab%3Daccount"));
        // Behind a proxy that rewrites Host, the page's address differs from Host; only its path is used.
        mockMvc.perform(post("/setup/password").header("Host", "backend:8080").header("Origin", "http://backend:8080")
                        .header("Referer", "https://home.example.org/setup").accept("text/html"))
                .andExpect(status().isSeeOther())
                .andExpect(redirectedUrl("/login?next=%2Fsetup"));
        mockMvc.perform(post("/setup/password").header("Host", "localhost").header("Origin", "http://localhost")
                        .header("Referer", "http://localhost//elsewhere.example/x").accept("text/html"))
                .andExpect(status().isSeeOther())
                .andExpect(redirectedUrl("/login?next=%2F"));
    }

    /** What a page asks when its live updates stopped: is the login gone, or only the server? */
    @Test
    void theSessionAnswersWhetherThisBrowserMayStillSeeTheApp() throws Exception {
        mockMvc.perform(get("/session")).andExpect(status().isNoContent());

        storeAFirstSecret();
        mockMvc.perform(get("/session")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/session").session(loggedIn())).andExpect(status().isNoContent());
        mockMvc.perform(get("/session").cookie(rememberedLogin())).andExpect(status().isNoContent());
    }

    @Test
    void theLoginPageLetsARememberedBrowserStraightThrough() throws Exception {
        storeAFirstSecret();
        Cookie remembered = rememberedLogin();

        mockMvc.perform(get("/login").param("next", "/setup").cookie(remembered))
                .andExpect(redirectedUrl("/setup"));
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
    void healthAnswersWithoutALoginWhenOneIsRequired() throws Exception {
        storeAFirstSecret();

        // As the container's HEALTHCHECK asks: from inside the container, without a session.
        mockMvc.perform(get("/health").header("Host", "127.0.0.1"))
                .andExpect(status().isOk())
                .andExpect(content().string("ok"))
                .andExpect(header().doesNotExist("Set-Cookie"));
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

    @Test
    void theLoginPageCanShowTheChosenThemeBeforeLoggingIn() throws Exception {
        storeAFirstSecret();

        mockMvc.perform(get("/login")).andExpect(status().isOk())
                .andExpect(content().string(allOf(containsString("href=\"" + themes.require("default").stylesheet() + "\""),
                        containsString("src=\"/themes/catalog.js\""),
                        containsString("src=\"/js/theme.js\""))));
        for (String path : new String[] {"/themes/catalog.js", "/themes/catalog.json", "/js/theme.js"}) {
            mockMvc.perform(get(path)).andExpect(status().isOk());
        }
        for (var theme : themes.themes()) {
            for (String path : theme.assets()) {
                mockMvc.perform(get(path)).andExpect(status().isOk());
            }
        }

        String stylesheet = themes.require("cyberpunk").stylesheet();
        for (String path : new String[] {"/themes/..;/setup", "/js/theme.js;x", "/themes/catalog.json;x",
                "/themes/catalog.js/extra", stylesheet + ";x", stylesheet.replace("/cyberpunk/", "/%63yberpunk/"),
                stylesheet.replace("theme.css", "../theme.css"), stylesheet.replace("theme.css", "theme.json"),
                stylesheet.replace("theme.css", "LICENSE"), stylesheet.replace("theme.css", "assets/OFL.txt"),
                stylesheet.replace("theme.css", "assets/missing.png")}) {
            mockMvc.perform(get(URI.create(path))).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void recoveryLoginKeepsDefaultStylingThroughARejectedPassword() throws Exception {
        storeAFirstSecret();
        String recovery = "/setup/appearance/recovery";

        mockMvc.perform(get(recovery).accept("text/html"))
                .andExpect(redirectedUrl("/login?next=%2Fsetup%2Fappearance%2Frecovery"));
        mockMvc.perform(get("/login").param("next", recovery)).andExpect(status().isOk())
                .andExpect(content().string(allOf(containsString("data-theme=\"default\""),
                        containsString("name=\"theme-recovery\" content=\"true\""),
                        containsString("href=\"" + themes.require("default").stylesheet() + "\""))));
        mockMvc.perform(post("/login").param("next", recovery).param("password", "wrong password"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(containsString("name=\"theme-recovery\" content=\"true\"")));
        mockMvc.perform(get("/login")).andExpect(status().isOk())
                .andExpect(content().string(not(containsString("name=\"theme-recovery\""))));
    }

    @Test
    void themeManagementAndDownloadsRequireLoginWhenAHouseholdPasswordExists() throws Exception {
        storeAFirstSecret();

        mockMvc.perform(get("/setup/appearance").accept("text/html"))
                .andExpect(redirectedUrl("/login?next=%2Fsetup%2Fappearance"));
        mockMvc.perform(get("/setup/appearance/default/export")).andExpect(status().isUnauthorized());
        mockMvc.perform(multipart("/setup/appearance/preview")
                        .file(new MockMultipartFile("package", "theme.zip", "application/zip", themes.export("default"))))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/setup/appearance/install").param("token", "unreviewed"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/setup/appearance/default/remove")).andExpect(status().isUnauthorized());

        MockHttpSession session = loggedIn();
        mockMvc.perform(get("/setup/appearance").session(session)).andExpect(status().isOk());
        mockMvc.perform(get("/setup/appearance/default/export").session(session))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("application/zip"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().bytes(themes.export("default")));
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

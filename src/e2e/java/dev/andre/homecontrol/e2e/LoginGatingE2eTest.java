package dev.andre.homecontrol.e2e;

import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.DeviceStatus;
import dev.andre.homecontrol.security.LoginService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/** The login gate as the browser sees it (D6): redirect, wrong password, and every open PWA asset. */
class LoginGatingE2eTest extends E2eApplicationTest {

    private static final String PASSWORD = "correct horse";
    private static final String TOKEN = "not-a-real-token";

    @Autowired
    private LoginService login;

    @BeforeEach
    void storeASecretAndRequireLogin() {
        login.storeSecrets(Map.of("e2e.token", TOKEN), PASSWORD, PASSWORD, new MockHttpServletRequest());
    }

    @AfterEach
    void removeTheSecret() {
        login.removeSecrets(List.of("e2e.token"));
    }

    @BrowserTest
    void theDashboardRedirectsToLoginAndOpensAfterThePassword(String browser) {
        try (BrowserSession session = open(browser)) {
            Page page = session.page();
            page.navigate("/?device=living");
            assertThat(page).hasURL(Pattern.compile(".*/login.*"));
            page.screenshot(new Page.ScreenshotOptions().setPath(java.nio.file.Path.of(
                    System.getProperty("e2e.artifacts", "build/e2e-artifacts"), "ui-login-" + browser + ".png")));


            page.locator("input[name=password]").fill("wrong");
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Log in")).click();
            assertThat(page.locator(".error")).isVisible();
            assertThat(page).hasURL(Pattern.compile(".*/login.*"));

            page.locator("input[name=password]").fill(PASSWORD);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Log in")).click();
            assertThat(page).hasURL(Pattern.compile(".*device=living.*"));
            assertThat(page.locator(".rail[data-rail='e2e/picks']")).isVisible();

            // /events is authorised: a pushed state reaches the logged-in tab.
            fakeDevices.push("living", DeviceState.initial());
            assertThat(page.locator("#status-living")).hasText("DISCONNECTED");
            fakeDevices.push("living", new DeviceState(DeviceStatus.CONNECTED, true, "com.example.launcher",
                    0, 0, false, Instant.now()));
            assertThat(page.locator("#status-living")).hasText("CONNECTED");

            // /devices/{id}/route-preview is authorised: the sheet resolves a real route, not an error.
            page.locator("button.tile[data-item='clip-1']").click();
            assertThat(page.locator("#sheet-route")).containsText("Open in the YouTube app");
        }
    }

    @BrowserTest
    void pwaFilesStayReachableWithoutASession(String browser) {
        try (BrowserSession session = open(browser)) {
            APIRequestContext api = session.context().request();

            APIResponse manifest = api.get(baseUrl() + "/manifest.webmanifest");
            org.assertj.core.api.Assertions.assertThat(manifest.status()).isEqualTo(200);
            org.assertj.core.api.Assertions.assertThat(manifest.text()).contains("\"name\":\"Home Control\"");

            APIResponse icon = api.get(baseUrl() + "/icons/icon-192.png");
            org.assertj.core.api.Assertions.assertThat(icon.status()).isEqualTo(200);
            org.assertj.core.api.Assertions.assertThat(icon.headers().get("content-type")).contains("image/png");

            org.assertj.core.api.Assertions.assertThat(api.get(baseUrl() + "/events").status()).isEqualTo(401);
            org.assertj.core.api.Assertions.assertThat(api.get(baseUrl() + "/rails").status()).isEqualTo(401);
            org.assertj.core.api.Assertions.assertThat(
                    api.get(baseUrl() + "/devices/living/route-preview?source=e2e&item=clip-1").status()).isEqualTo(401);
        }
    }

    @BrowserTest
    void theTokenNeverReachesThePage(String browser) {
        try (BrowserSession session = open(browser)) {
            Page page = session.page();
            page.navigate("/?device=living");
            page.locator("input[name=password]").fill(PASSWORD);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Log in")).click();
            assertThat(page).hasURL(Pattern.compile(".*device=living.*"));

            org.assertj.core.api.Assertions.assertThat(page.content()).doesNotContain(TOKEN);

            APIResponse rails = page.request().get(baseUrl() + "/rails");
            org.assertj.core.api.Assertions.assertThat(rails.text()).doesNotContain(TOKEN);
        }
    }
}

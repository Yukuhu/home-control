package dev.andre.homecontrol.e2e;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;

import java.util.ArrayList;
import java.util.List;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

class ThemeE2eTest extends E2eApplicationTest {

    private static final String THEME_KEY = "homecontrol.theme.v1";

    @BrowserTest
    void theHeaderButtonSwitchesThemesLiveWithoutReloadingThePage(String browser) {
        try (BrowserSession session = open(browser)) {
            Page page = session.page();
            page.navigate("/?device=living");
            Locator html = page.locator("html");
            Locator toggle = themeToggle(page);
            assertThat(html).hasAttribute("data-theme", "default");
            assertThat(toggle).hasAttribute("aria-pressed", "false");
            String defaultBackground = (String) page.locator("body")
                    .evaluate("body => getComputedStyle(body).backgroundColor");
            // A reload would drop this marker, so it proves the switch happens in place.
            page.evaluate("() => { window.themeSwitchProbe = true; }");

            toggle.click();

            assertThat(html).hasAttribute("data-theme", "cyberpunk");
            assertThat(toggle).hasAttribute("aria-pressed", "true");
            assertThat(page.locator("body")).hasCSS("background-color", "rgb(7, 8, 13)");
            assertThat(page.locator("meta[name='theme-color']")).hasAttribute("content", "#07080d");
            org.assertj.core.api.Assertions.assertThat(page.evaluate("() => window.themeSwitchProbe"))
                    .as("The page was not reloaded").isEqualTo(true);

            toggle.click();

            assertThat(html).hasAttribute("data-theme", "default");
            assertThat(toggle).hasAttribute("aria-pressed", "false");
            assertThat(page.locator("body")).hasCSS("background-color", defaultBackground);
            assertThat(page.locator("meta[name='theme-color']")).hasAttribute("content", "#101917");
        }
    }

    @BrowserTest
    void theChosenThemeStaysOnAcrossPagesAndReloadsFromTheFirstPaint(String browser) {
        try (BrowserSession session = open(browser)) {
            Page page = session.page();
            page.navigate("/?device=living");
            themeToggle(page).click();
            assertThat(page.locator("html")).hasAttribute("data-theme", "cyberpunk");

            // Record the theme as soon as <body> exists, before any module script or paint.
            session.context().addInitScript("""
                    new MutationObserver((changes, observer) => {
                        if (!document.body) return;
                        window.themeAtFirstBody = document.documentElement.dataset.theme;
                        observer.disconnect();
                    }).observe(document, { childList: true, subtree: true });
                    """);
            for (String path : new String[] {"/setup", "/?device=living", "/offline.html"}) {
                page.navigate(path);
                assertThat(page.locator("html")).hasAttribute("data-theme", "cyberpunk");
                org.assertj.core.api.Assertions.assertThat(page.evaluate("() => window.themeAtFirstBody"))
                        .as("%s starts in the saved theme", path).isEqualTo("cyberpunk");
            }
            page.navigate("/setup");
            assertThat(themeToggle(page)).hasAttribute("aria-pressed", "true");

            themeToggle(page).click();
            page.reload();
            assertThat(page.locator("html")).hasAttribute("data-theme", "default");
            assertThat(themeToggle(page)).hasAttribute("aria-pressed", "false");
        }
    }

    @BrowserTest
    void anotherOpenTabFollowsASwitch(String browser) {
        try (BrowserSession session = open(browser)) {
            Page first = session.page();
            first.navigate("/?device=living");
            Page second = session.context().newPage();
            second.navigate("/setup");

            themeToggle(first).click();

            assertThat(second.locator("html")).hasAttribute("data-theme", "cyberpunk");
            assertThat(themeToggle(second)).hasAttribute("aria-pressed", "true");
        }
    }

    @BrowserTest
    void theKeyboardSwitchesThemesWithoutSendingATvKey(String browser) {
        try (BrowserSession session = open(browser)) {
            Page page = session.page();
            List<String> keyRequests = new ArrayList<>();
            page.onRequest(request -> {
                if (request.url().matches(".*/devices/[^/]+/key/.*")) keyRequests.add(request.url());
            });
            page.navigate("/?device=living");
            themeToggle(page).focus();

            page.keyboard().press("Enter");
            assertThat(page.locator("html")).hasAttribute("data-theme", "cyberpunk");
            page.keyboard().press("Space");
            assertThat(page.locator("html")).hasAttribute("data-theme", "default");

            org.assertj.core.api.Assertions.assertThat(keyRequests).isEmpty();
            org.assertj.core.api.Assertions.assertThat(fakeDevices.recorded("living")).isEmpty();
        }
    }

    @BrowserTest
    void anUnknownSavedThemeFallsBackToTheDefault(String browser) {
        try (BrowserSession session = open(browser)) {
            session.context().addInitScript("localStorage.setItem('" + THEME_KEY + "', 'toString')");
            Page page = session.page();
            page.navigate("/setup");
            assertThat(page.locator("html")).hasAttribute("data-theme", "default");
            assertThat(themeToggle(page)).hasAttribute("aria-pressed", "false");
        }
    }

    private static Locator themeToggle(Page page) {
        return page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Cyberpunk theme"));
    }
}

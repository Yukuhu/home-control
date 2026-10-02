package dev.andre.homecontrol.e2e;

import com.microsoft.playwright.Download;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Route;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.FilePayload;
import com.microsoft.playwright.options.ForcedColors;
import com.microsoft.playwright.options.ReducedMotion;
import com.microsoft.playwright.options.ScreenshotAnimations;
import com.microsoft.playwright.options.WaitUntilState;
import dev.andre.homecontrol.themes.ThemeCatalog;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ThemeE2eTest extends E2eApplicationTest {

    private static final String THEME_KEY = "homecontrol.theme.v1";
    private static final String DECORATED_CONTROLS = """
            :root { & button {
                animation: theme-pulse 1s linear infinite;
                transition: background-color 2s;
                clip-path: polygon(0 0, 100% 0, 100% 100%);
                outline: none;
            } }
            @keyframes theme-pulse { to { opacity: .5; } }
            """;

    @Autowired
    private ThemeCatalog themes;

    @AfterEach
    void removeImportedThemes() {
        themes.themes().stream().filter(theme -> !theme.builtIn()).forEach(theme -> themes.remove(theme.id()));
    }

    @BrowserTest
    void theHeaderPickerSwitchesThemesLiveWithoutReloadingThePage(String browser) {
        try (BrowserSession session = open(browser)) {
            Page page = session.page();
            page.navigate("/?device=living");
            Locator html = page.locator("html");
            Locator picker = themePicker(page);
            assertThat(html).hasAttribute("data-theme", "default");
            assertThat(picker).hasValue("default");
            String defaultBackground = (String) page.locator("body")
                    .evaluate("body => getComputedStyle(body).backgroundColor");
            page.evaluate("() => { window.themeSwitchProbe = true; }");

            picker.selectOption("cyberpunk");

            assertThat(html).hasAttribute("data-theme", "cyberpunk");
            assertThat(picker).hasValue("cyberpunk");
            assertThat(page.locator("body")).hasCSS("background-color", "rgb(7, 8, 13)");
            assertThat(page.locator("meta[name='theme-color']")).hasAttribute("content", "#07080d");
            org.assertj.core.api.Assertions.assertThat(page.evaluate("() => window.themeSwitchProbe"))
                    .as("The page was not reloaded").isEqualTo(true);

            picker.selectOption("default");

            assertThat(html).hasAttribute("data-theme", "default");
            assertThat(picker).hasValue("default");
            assertThat(page.locator("body")).hasCSS("background-color", defaultBackground);
            assertThat(page.locator("meta[name='theme-color']")).hasAttribute("content", "#101917");
        }
    }

    @BrowserTest
    void theChosenThemeStaysOnAcrossPagesAndReloadsFromTheFirstVisibleFrame(String browser) {
        try (BrowserSession session = open(browser)) {
            Page page = session.page();
            page.navigate("/?device=living");
            themePicker(page).selectOption("cyberpunk");
            assertThat(page.locator("html")).hasAttribute("data-theme", "cyberpunk");

            session.context().addInitScript("""
                    function firstVisibleFrame() {
                        if (!document.body || getComputedStyle(document.documentElement).visibility === 'hidden') {
                            requestAnimationFrame(firstVisibleFrame);
                            return;
                        }
                        window.firstVisibleTheme = {
                            id: document.documentElement.dataset.theme,
                            background: getComputedStyle(document.body).backgroundColor
                        };
                    }
                    requestAnimationFrame(firstVisibleFrame);
                    """);
            for (String path : new String[] {"/setup", "/?device=living", "/offline.html"}) {
                page.navigate(path);
                assertThat(page.locator("html")).hasAttribute("data-theme", "cyberpunk");
                page.waitForFunction("() => Boolean(window.firstVisibleTheme)");
                org.assertj.core.api.Assertions.assertThat(page.evaluate("() => window.firstVisibleTheme.id"))
                        .as("%s first becomes visible in the saved theme", path).isEqualTo("cyberpunk");
                org.assertj.core.api.Assertions.assertThat(page.evaluate("() => window.firstVisibleTheme.background"))
                        .isEqualTo("rgb(7, 8, 13)");
            }
            page.navigate("/setup");
            assertThat(themePicker(page)).hasValue("cyberpunk");
            themePicker(page).selectOption("default");
            page.reload();
            assertThat(page.locator("html")).hasAttribute("data-theme", "default");
            assertThat(themePicker(page)).hasValue("default");
        }
    }

    @BrowserTest
    void anotherOpenTabRefreshesItsCatalogToFollowANewTheme(String browser) throws IOException {
        try (BrowserSession session = open(browser)) {
            Page first = session.page();
            first.navigate("/?device=living");
            Page second = session.context().newPage();
            second.navigate("/setup");
            themePicker(first).selectOption("cyberpunk");
            assertThat(second.locator("html")).hasAttribute("data-theme", "cyberpunk");

            themes.install(ThemePackageFixtures.derivative(themes.export("default"), "ocean", "Ocean", "#123456"), null);
            first.evaluate("() => window.homeControlTheme.refresh()");
            themePicker(first).selectOption("ocean");
            assertThat(second.locator("html")).hasAttribute("data-theme", "ocean");
            assertThat(themePicker(second)).hasValue("ocean");
            assertThat(second.locator("body")).hasCSS("background-color", "rgb(18, 52, 86)");

            String previousRevision = themes.require("ocean").revision();
            themes.install(ThemePackageFixtures.derivative(themes.export("ocean"), "ocean", "Ocean updated", "#654321"),
                    previousRevision);
            second.evaluate("() => window.homeControlTheme.refresh()");
            assertThat(second.locator("body")).hasCSS("background-color", "rgb(101, 67, 33)");
            assertThat(second.locator("link[data-theme-stylesheet=ocean]"))
                    .hasAttribute("href", themes.require("ocean").stylesheet());

            themes.remove("ocean");
            second.evaluate("() => window.dispatchEvent(new Event('focus'))");
            assertThat(second.locator("html")).hasAttribute("data-theme", "default");
            assertThat(themePicker(second).locator("option[value=ocean]")).hasCount(0);
        }
    }

    @BrowserTest
    void theKeyboardSelectsThemesWithoutSendingATvKey(String browser) {
        try (BrowserSession session = open(browser)) {
            Page page = session.page();
            List<String> keyRequests = new ArrayList<>();
            page.onRequest(request -> {
                if (request.url().matches(".*/devices/[^/]+/key/.*")) keyRequests.add(request.url());
            });
            page.navigate("/?device=living");
            themePicker(page).focus();
            page.keyboard().press("ArrowDown");
            page.keyboard().press("Enter");
            assertThat(page.locator("html")).hasAttribute("data-theme", "cyberpunk");
            page.keyboard().press("Home");
            page.keyboard().press("Enter");
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
            assertThat(themePicker(page)).hasValue("default");
        }
    }

    @BrowserTest
    void aThemeExportCanBeEditedImportedSelectedAndSharedAgain(String browser) throws IOException {
        byte[] shared;
        try (BrowserSession session = open(browser)) {
            Page page = session.page();
            page.navigate("/setup/appearance");
            for (String id : List.of("default", "cyberpunk")) {
                assertThat(card(page, id).getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Remove")))
                        .hasCount(0);
            }
            Download exported = page.waitForDownload(() -> card(page, "cyberpunk")
                    .getByRole(AriaRole.LINK, new Locator.GetByRoleOptions().setName("Export")).click());
            byte[] derivative = ThemePackageFixtures.derivative(Files.readAllBytes(exported.path()), "ocean", "Ocean", "#123456");
            importTheme(page, derivative);
            assertThat(card(page, "ocean")).isVisible();
            assertThat(page.locator("html")).hasAttribute("data-theme", "default");
            card(page, "ocean").getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Use theme")).click();
            assertThat(page.locator("html")).hasAttribute("data-theme", "ocean");
            assertThat(page.locator("body")).hasCSS("background-color", "rgb(18, 52, 86)");
            screenshotAppearance(page, browser);
            Download reshare = page.waitForDownload(() -> card(page, "ocean")
                    .getByRole(AriaRole.LINK, new Locator.GetByRoleOptions().setName("Export")).click());
            shared = Files.readAllBytes(reshare.path());
            card(page, "ocean").getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Remove")).click();
            assertThat(card(page, "ocean")).hasCount(0);
            assertThat(page.locator("html")).hasAttribute("data-theme", "default");
        }
        try (BrowserSession recipient = open(browser)) {
            Page page = recipient.page();
            page.navigate("/setup/appearance");
            importTheme(page, shared);
            themePicker(page).selectOption("ocean");
            assertThat(page.locator("html")).hasAttribute("data-theme", "ocean");
            page.navigate("/setup");
            assertThat(page.locator("html")).hasAttribute("data-theme", "ocean");
            assertThat(page.locator("body")).hasCSS("background-color", "rgb(18, 52, 86)");
        }
    }

    @BrowserTest
    void aFailedStylesheetKeepsTheWorkingThemeAndDoesNotSaveTheChoice(String browser) {
        try (BrowserSession session = open(browser)) {
            Page page = session.page();
            page.navigate("/setup");
            page.route("**" + themes.require("cyberpunk").stylesheet(), Route::abort);
            themePicker(page).selectOption("cyberpunk");
            assertThat(page.locator("[data-theme-status]")).containsText("Could not load Cyberpunk");
            assertThat(page.locator("html")).hasAttribute("data-theme", "default");
            assertThat(themePicker(page)).hasValue("default");
            org.assertj.core.api.Assertions.assertThat(page.evaluate("() => localStorage.getItem('" + THEME_KEY + "')"))
                    .isNull();
        }
    }

    @BrowserTest
    void aStalledSavedStylesheetRevealsDefaultWithinTheLoadingGuard(String browser) {
        try (BrowserSession session = open(browser)) {
            session.context().addInitScript("localStorage.setItem('" + THEME_KEY + "', 'cyberpunk')");
            Page page = session.page();
            AtomicReference<Route> stalled = new AtomicReference<>();
            page.route("**" + themes.require("cyberpunk").stylesheet(), stalled::set);
            page.navigate("/setup", new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            page.evaluate("() => window.homeControlTheme.refresh()");
            assertThat(page.locator("[data-theme-status]")).containsText("Default has been restored");
            assertThat(page.locator("html")).hasAttribute("data-theme", "default");
            assertThat(themePicker(page)).isVisible();
            if (stalled.get() != null) stalled.get().abort();
        }
    }

    @BrowserTest
    void anOlderStylesheetCannotOverrideALaterSelection(String browser) {
        try (BrowserSession session = open(browser)) {
            Page page = session.page();
            page.navigate("/setup");
            AtomicReference<Route> delayed = new AtomicReference<>();
            page.route("**" + themes.require("cyberpunk").stylesheet(), delayed::set);
            themePicker(page).selectOption("cyberpunk");
            page.waitForCondition(() -> delayed.get() != null);
            APIResponse stylesheet = delayed.get().fetch();
            themePicker(page).selectOption("default");
            delayed.get().fulfill(new Route.FulfillOptions().setResponse(stylesheet));
            page.evaluate("() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))");
            assertThat(page.locator("html")).hasAttribute("data-theme", "default");
            assertThat(themePicker(page)).hasValue("default");
            org.assertj.core.api.Assertions.assertThat(page.evaluate("() => localStorage.getItem('" + THEME_KEY + "')"))
                    .isEqualTo("default");
        }
    }

    @BrowserTest
    void deniedStorageKeepsTheSelectedThemeForTheCurrentPage(String browser) {
        try (BrowserSession session = open(browser)) {
            session.context().addInitScript("""
                    Object.defineProperty(window, 'localStorage', {
                        get() { throw new DOMException('Storage denied', 'SecurityError'); }
                    });
                    """);
            Page page = session.page();
            page.navigate("/setup");
            themePicker(page).selectOption("cyberpunk");
            assertThat(page.locator("html")).hasAttribute("data-theme", "cyberpunk");
            page.evaluate("() => window.homeControlTheme.refresh()");
            assertThat(themePicker(page)).hasValue("cyberpunk");
            page.reload();
            assertThat(page.locator("html")).hasAttribute("data-theme", "default");
        }
    }

    @BrowserTest
    void recoveryIgnoresTheSavedThemeAndOtherTabsUntilAnExplicitReset(String browser) throws IOException {
        themes.install(ThemePackageFixtures.derivative(themes.export("cyberpunk"), "ocean", "Ocean", "#123456"), null);
        try (BrowserSession session = open(browser)) {
            Page first = session.page();
            first.navigate("/setup");
            themePicker(first).selectOption("ocean");
            assertThat(first.locator("html")).hasAttribute("data-theme", "ocean");
            Page recovery = session.context().newPage();
            List<String> importedRequests = new ArrayList<>();
            recovery.onRequest(request -> { if (request.url().contains("/themes/packages/ocean/")) importedRequests.add(request.url()); });
            recovery.navigate("/setup/appearance/recovery");
            assertThat(recovery.locator("html")).hasAttribute("data-theme", "default");
            assertThat(themePicker(recovery)).isDisabled();
            themePicker(first).selectOption("cyberpunk");
            assertThat(first.locator("html")).hasAttribute("data-theme", "cyberpunk");
            assertThat(recovery.locator("html")).hasAttribute("data-theme", "default");
            recovery.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Reset to Default")).click();
            assertThat(first.locator("html")).hasAttribute("data-theme", "default");
            org.assertj.core.api.Assertions.assertThat(recovery.evaluate("() => localStorage.getItem('" + THEME_KEY + "')"))
                    .isEqualTo("default");
            org.assertj.core.api.Assertions.assertThat(importedRequests).isEmpty();
        }
    }

    @BrowserTest
    void reducedMotionAndKeyboardFocusOverrideImportedControlDecorations(String browser) throws IOException {
        themes.install(ThemePackageFixtures.derivative(themes.export("default"), "decorated", "Decorated", "#101917",
                DECORATED_CONTROLS), null);
        try (BrowserSession session = open(browser)) {
            Page page = session.page();
            page.emulateMedia(new Page.EmulateMediaOptions().setReducedMotion(ReducedMotion.NO_PREFERENCE));
            page.navigate("/setup/workflows/new");
            themePicker(page).selectOption("decorated");
            assertThat(page.locator("html")).hasAttribute("data-theme", "decorated");
            Locator save = page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Save workflow"));
            org.assertj.core.api.Assertions.assertThat(save.evaluate("button => getComputedStyle(button).animationName"))
                    .as("The imported decoration is active before reduced motion is requested").isNotEqualTo("none");
            assertThat(save).hasCSS("transition-duration", "2s");
            page.emulateMedia(new Page.EmulateMediaOptions().setReducedMotion(ReducedMotion.REDUCE));
            assertThat(save).hasCSS("animation-name", "none");
            assertThat(save).hasCSS("transition-duration", "0s");
            page.keyboard().press("Tab");
            save.focus();
            assertThat(save).hasCSS("outline-style", "solid");
            assertThat(save).hasCSS("outline-width", "2px");
        }
    }

    @BrowserTest
    void forcedColorsRemoveClippingAndPreserveAVisibleFocusOutline(String browser) throws IOException {
        themes.install(ThemePackageFixtures.derivative(themes.export("default"), "decorated", "Decorated", "#101917",
                DECORATED_CONTROLS), null);
        try (BrowserSession session = open(browser)) {
            Page page = session.page();
            page.navigate("/setup/workflows/new");
            themePicker(page).selectOption("decorated");
            assertThat(page.locator("html")).hasAttribute("data-theme", "decorated");
            Locator save = page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Save workflow"));
            org.assertj.core.api.Assertions.assertThat(save.evaluate("button => getComputedStyle(button).clipPath"))
                    .isNotEqualTo("none");
            page.emulateMedia(new Page.EmulateMediaOptions().setForcedColors(ForcedColors.ACTIVE));
            assumeTrue((Boolean) page.evaluate("() => matchMedia('(forced-colors: active)').matches"),
                    "This browser does not emulate forced colors");
            assertThat(save).hasCSS("clip-path", "none");
            assertThat(save).hasCSS("background-image", "none");
            page.keyboard().press("Tab");
            save.focus();
            assertThat(save).hasCSS("outline-style", "solid");
            assertThat(save).hasCSS("outline-width", "2px");
            String highlight = (String) page.evaluate("""
                    () => {
                        const sample = document.createElement('span');
                        sample.style.color = 'Highlight';
                        document.body.append(sample);
                        const color = getComputedStyle(sample).color;
                        sample.remove();
                        return color;
                    }
                    """);
            assertThat(save).hasCSS("outline-color", highlight);
        }
    }

    @BrowserTest
    void anImportedPseudoElementCannotInterceptControls(String browser) throws IOException {
        themes.install(ThemePackageFixtures.derivative(themes.export("default"), "overlay", "Overlay", "#101917", """
                :root { & .auth-page::after { content: ""; position: fixed; inset: 0; z-index: 9999; } }
                """), null);
        try (BrowserSession session = open(browser)) {
            Page page = session.page();
            page.navigate("/offline.html");
            page.evaluate("() => window.homeControlTheme.select('overlay')");
            assertThat(page.locator("html")).hasAttribute("data-theme", "overlay");
            org.assertj.core.api.Assertions.assertThat(page.evaluate("() => getComputedStyle(document.body, '::after').position"))
                    .as("The imported full-screen decoration exists").isEqualTo("fixed");
            Locator retry = page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Try again").setExact(true));
            org.assertj.core.api.Assertions.assertThat(retry.evaluate("""
                    link => {
                        const rect = link.getBoundingClientRect();
                        return document.elementFromPoint(rect.x + rect.width / 2, rect.y + rect.height / 2)?.closest('a') === link;
                    }
                    """)).as("The app link receives pointer input through the decoration").isEqualTo(true);
            retry.click();
            assertThat(page.locator("[data-theme-picker]")).isVisible();
        }
    }

    @BrowserTest
    void bothBuiltInThemesKeepTheWorkflowEditorUsableOnPhoneAndDesktop(String browser) {
        try (BrowserSession session = open(browser)) {
            Page page = session.page();
            page.navigate("/setup/workflows/new");
            for (String id : List.of("default", "cyberpunk")) {
                themePicker(page).selectOption(id);
                assertThat(page.locator("html")).hasAttribute("data-theme", id);
                for (int width : new int[] {390, 1440}) {
                    page.setViewportSize(width, width == 390 ? 844 : 1000);
                    page.getByLabel("Workflow name", new Page.GetByLabelOptions().setExact(true)).fill("Theme review");
                    assertThat(page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Save workflow")))
                            .isVisible();
                    Number overflow = (Number) page.evaluate("""
                            () => Math.max(document.documentElement.scrollWidth, document.body.scrollWidth)
                                - document.documentElement.clientWidth
                            """);
                    org.assertj.core.api.Assertions.assertThat(overflow.doubleValue())
                            .as("%s workflow editor fits at %s pixels", id, width).isLessThanOrEqualTo(1);
                    page.screenshot(new Page.ScreenshotOptions().setPath(Path.of(
                            System.getProperty("e2e.artifacts", "build/e2e-artifacts"),
                            "ui-" + id + "-workflow-" + width + "-" + browser + ".png"))
                            .setFullPage(true).setAnimations(ScreenshotAnimations.DISABLED));
                }
            }
        }
    }

    private static Locator themePicker(Page page) {
        return page.getByRole(AriaRole.COMBOBOX, new Page.GetByRoleOptions().setName("Theme").setExact(true));
    }

    private static void screenshotAppearance(Page page, String browser) {
        for (int width : new int[] {390, 1440}) {
            page.setViewportSize(width, width == 390 ? 844 : 1000);
            page.evaluate("() => window.scrollTo(0, 0)");
            page.screenshot(new Page.ScreenshotOptions().setPath(Path.of(
                    System.getProperty("e2e.artifacts", "build/e2e-artifacts"),
                    "ui-imported-appearance-" + width + "-" + browser + ".png"))
                    .setFullPage(true).setAnimations(ScreenshotAnimations.DISABLED));
        }
    }

    private static Locator card(Page page, String id) {
        return page.locator("[data-theme-id=" + id + "]");
    }

    private static void importTheme(Page page, byte[] bytes) {
        page.locator("input[name=package]").setInputFiles(new FilePayload("ocean.zip", "application/zip", bytes));
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Review theme")).click();
        assertThat(page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Install this theme?"))).isVisible();
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Install theme").setExact(true)).click();
        assertThat(card(page, "ocean")).isVisible();
    }
}

package dev.andre.homecontrol.e2e;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.BoundingBox;
import com.microsoft.playwright.options.ScreenshotAnimations;
import dev.andre.homecontrol.content.SourcePreferencesService;
import dev.andre.homecontrol.core.content.ContentSource;
import dev.andre.homecontrol.core.content.ContentSources;
import dev.andre.homecontrol.core.content.SourcePreferences;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

class InterfaceE2eTest extends E2eApplicationTest {

    @Autowired
    private SourcePreferencesService sourcePreferences;

    @Autowired
    private ContentSources contentSources;

    @BrowserTest
    void enterOnTheRemoteToggleOpensTheDrawerWithoutSendingATvKey(String browser) {
        try (BrowserSession session = open(browser)) {
            Page page = session.page();
            List<String> keyRequests = watchRemoteKeys(page);
            page.navigate("/?device=living");
            Locator toggle = page.locator(".drawer-toggle");

            toggle.focus();
            page.keyboard().press("Enter");

            assertThat(page.locator("#remote-drawer")).isVisible();
            assertThat(toggle).hasAttribute("aria-expanded", "true");
            org.assertj.core.api.Assertions.assertThat(keyRequests).isEmpty();
            org.assertj.core.api.Assertions.assertThat(fakeDevices.recorded("living")).isEmpty();
        }
    }

    @BrowserTest
    void escapeClosesTheRemoteAndReturnsFocusToItsToggle(String browser) {
        try (BrowserSession session = open(browser)) {
            Page page = session.page();
            page.navigate("/?device=living");
            Locator toggle = page.locator(".drawer-toggle");
            toggle.click();
            assertThat(page.locator("#remote-drawer")).isVisible();
            org.assertj.core.api.Assertions.assertThat(page.evaluate("""
                    () => document.getElementById('remote-drawer').contains(document.activeElement)
                    """)).as("Opening the remote moves keyboard focus into its controls").isEqualTo(true);

            page.keyboard().press("Escape");

            assertThat(page.locator("#remote-drawer")).isHidden();
            assertThat(toggle).hasAttribute("aria-expanded", "false");
            assertThat(toggle).isFocused();
        }
    }

    @BrowserTest
    void enterOnAContentTileOpensItsPlaySheetWithoutSendingATvKey(String browser) {
        try (BrowserSession session = open(browser)) {
            Page page = session.page();
            List<String> keyRequests = watchRemoteKeys(page);
            page.navigate("/?device=living");
            page.locator("button.tile[data-item='clip-1']").focus();

            page.keyboard().press("Enter");

            assertThat(page.locator("#play-sheet")).isVisible();
            assertThat(page.locator("#sheet-title")).hasText("Big Buck Bunny");
            assertThat(page.locator("#sheet-play")).isEnabled();
            org.assertj.core.api.Assertions.assertThat(keyRequests).isEmpty();
            org.assertj.core.api.Assertions.assertThat(fakeDevices.recorded("living")).isEmpty();
        }
    }

    @BrowserTest
    void arrowKeysSwitchRemoteTabsWithoutSendingATvKey(String browser) {
        try (BrowserSession session = open(browser)) {
            Page page = session.page();
            List<String> keyRequests = watchRemoteKeys(page);
            page.navigate("/?device=living&remote=open");
            Locator buttons = page.getByRole(AriaRole.TAB, new Page.GetByRoleOptions().setName("Buttons"));
            Locator touchpad = page.getByRole(AriaRole.TAB, new Page.GetByRoleOptions().setName("Touchpad"));
            buttons.focus();

            page.keyboard().press("ArrowRight");

            assertThat(touchpad).isFocused();
            assertThat(touchpad).hasAttribute("aria-selected", "true");
            assertThat(buttons).hasAttribute("aria-selected", "false");
            assertThat(page.locator("#touchpad")).isVisible();

            page.keyboard().press("ArrowRight");

            assertThat(buttons).isFocused();
            assertThat(buttons).hasAttribute("aria-selected", "true");
            assertThat(page.locator("#touchpad")).isHidden();
            org.assertj.core.api.Assertions.assertThat(keyRequests).isEmpty();
            org.assertj.core.api.Assertions.assertThat(fakeDevices.recorded()).isEmpty();
        }
    }

    @BrowserTest
    void arrowKeysChooseThePlaybackDeviceAndPreviewItsRouteWithoutSendingATvKey(String browser) {
        try (BrowserSession session = open(browser)) {
            Page page = session.page();
            List<String> keyRequests = watchRemoteKeys(page);
            page.navigate("/?device=living");
            page.locator("button.tile[data-item='clip-1']").click();
            Locator living = page.locator("[data-sheet-device='living']");
            Locator speaker = page.locator("[data-sheet-device='speaker']");
            Locator bedroom = page.locator("[data-sheet-device='bedroom']");
            living.focus();

            page.keyboard().press("ArrowRight");

            assertThat(speaker).isFocused();
            assertThat(speaker).hasAttribute("aria-checked", "true");
            assertThat(living).hasAttribute("aria-checked", "false");
            assertThat(page.locator("#sheet-route")).containsText("Cannot play on Speaker");
            assertThat(page.locator("#sheet-play")).isDisabled();

            page.keyboard().press("ArrowRight");

            assertThat(bedroom).isFocused();
            assertThat(bedroom).hasAttribute("aria-checked", "true");
            assertThat(speaker).hasAttribute("aria-checked", "false");
            assertThat(page.locator("#sheet-route")).containsText("Play on Bedroom");
            assertThat(page.locator("#sheet-play")).isEnabled();
            org.assertj.core.api.Assertions.assertThat(keyRequests).isEmpty();
            org.assertj.core.api.Assertions.assertThat(fakeDevices.recorded()).isEmpty();
        }
    }

    @BrowserTest
    void firstRunGuidesTheUserToConnectADevice(String browser) {
        devices.devices().forEach(device -> devices.forget(device.id()));
        try (BrowserSession session = open(browser)) {
            Page page = session.page();
            page.navigate("/");
            assertThat(page).hasURL(baseUrl() + "/setup");
            assertThat(page.locator(".setup-welcome")).isVisible();
            assertThat(page.locator(".setup-steps li")).hasCount(3);
            for (int width : new int[] {390, 1440}) {
                page.setViewportSize(width, 900);
                assertNoPageOverflow(page);
                screenshot(page, browser, width, "first-run");
            }
            page.locator("summary").filter(new Locator.FilterOptions()
                    .setHasText("Add an Android TV by address")).click();
            page.getByLabel("IP address", new Page.GetByLabelOptions().setExact(true)).first().fill("192.168.1.50");
        }
    }

    @BrowserTest
    void setupFieldsHaveAccessibleLabelsThatIdentifyTheirControls(String browser) {
        try (BrowserSession session = open(browser)) {
            Page page = session.page();
            page.navigate("/setup");
            page.locator("summary").filter(new Locator.FilterOptions()
                    .setHasText("Add an Android TV by address")).click();

            Locator manualDevice = page.locator("form[action='/setup/pair']").filter(
                    new Locator.FilterOptions().setHas(page.locator("input[name='host']:not([type='hidden'])")));
            Locator address = manualDevice.getByLabel(Pattern.compile("address|host", Pattern.CASE_INSENSITIVE));
            address.fill("192.168.1.50");
            assertThat(address).isFocused();
            assertThat(manualDevice.locator("input[name='host']")).hasValue("192.168.1.50");

            Locator deviceName = manualDevice.getByLabel(Pattern.compile("name", Pattern.CASE_INSENSITIVE));
            deviceName.fill("Living Room TV");
            assertThat(manualDevice.locator("input[name='name']")).hasValue("Living Room TV");

            Locator jellyfin = page.locator("form[action='/setup/sources/jellyfin']");
            Locator username = jellyfin.getByLabel("Jellyfin user name",
                    new Locator.GetByLabelOptions().setExact(true));
            username.fill("home-viewer");
            assertThat(username).isFocused();
            assertThat(jellyfin.locator("input[name='userName']")).hasValue("home-viewer");

            for (Locator field : visibleFields(page).all()) {
                assertThat(field).hasAccessibleName(Pattern.compile(".*\\S.*", Pattern.DOTALL));
            }
        }
    }

    @BrowserTest
    void setupNavigationLinksReachTheirSections(String browser) {
        try (BrowserSession session = open(browser)) {
            Page page = session.page();
            page.navigate("/setup");
            Locator links = page.locator("nav a[href^='#']");
            org.assertj.core.api.Assertions.assertThat(links.count())
                    .as("Setup offers navigation to its sections").isPositive();

            for (Locator link : links.all()) {
                String hash = link.getAttribute("href");
                link.click();
                assertThat(page).hasURL(baseUrl() + "/setup" + hash);
                assertThat(page.locator(hash)).isVisible();
                page.waitForFunction("""
                        id => {
                            const target = document.getElementById(id);
                            const top = target.getBoundingClientRect().top;
                            return top >= -1 && top < window.innerHeight;
                        }
                        """, hash.substring(1));
            }
        }
    }

    @BrowserTest
    void dashboardRemoteAndSetupFitANarrowPhone(String browser) {
        verifyLayouts(browser, 320, 740);
    }

    @BrowserTest
    void dashboardRemoteAndSetupFitAPhone(String browser) {
        verifyLayouts(browser, 390, 844);
    }

    @BrowserTest
    void dashboardRemoteAndSetupFitADesktop(String browser) {
        verifyLayouts(browser, 1440, 1000);
    }

    @BrowserTest
    void dashboardWithoutContentOffersAWayToConnectASource(String browser) {
        SourcePreferences original = sourcePreferences.current();
        try (BrowserSession session = open(browser)) {
            sourcePreferences.update(preferences -> {
                for (ContentSource source : contentSources.all()) {
                    preferences = preferences.withSourceEnabled(source.id(), false);
                }
                return preferences;
            });
            Page page = session.page();
            for (int width : new int[] {390, 1440}) {
                page.setViewportSize(width, width == 390 ? 844 : 1000);
                page.navigate("/?device=living");
                assertThat(page.locator("#rails button.tile")).hasCount(0);
                Locator connect = page.getByRole(AriaRole.LINK,
                        new Page.GetByRoleOptions().setName(Pattern.compile("Connect a source")));
                assertThat(connect).isVisible();
                assertNoPageOverflow(page);
                assertInsideViewport(connect, width);
                screenshot(page, browser, width, "empty-dashboard");
                connect.click();
                assertThat(page).hasURL(Pattern.compile(".*/setup#connections$"));
            }
        } finally {
            sourcePreferences.update(ignored -> original);
        }
    }

    private void verifyLayouts(String browser, int width, int height) {
        try (BrowserSession session = open(browser)) {
            Page page = session.page();
            page.setViewportSize(width, height);
            page.navigate("/?device=living");
            Locator tile = page.locator("button.tile[data-item='clip-1']");
            assertThat(tile).isVisible();
            BoundingBox tileBounds = tile.boundingBox();
            BoundingBox artworkBounds = tile.locator(".art").boundingBox();
            org.assertj.core.api.Assertions.assertThat(artworkBounds.width)
                    .as("Artwork fills its content tile at %s pixels", width)
                    .isCloseTo(tileBounds.width, org.assertj.core.data.Offset.offset(2.0));
            assertNoPageOverflow(page);
            assertInsideViewport(page.locator("#search-q"), width);
            assertInsideViewport(page.locator(".drawer-toggle"), width);
            screenshot(page, browser, width, "dashboard");

            page.locator(".drawer-toggle").click();
            assertThat(page.locator("#remote-drawer")).isVisible();
            assertNoPageOverflow(page);
            assertInsideViewport(page.locator("#remote-drawer"), width);
            screenshot(page, browser, width, "drawer");

            page.navigate("/setup");
            assertNoPageOverflow(page);
            for (Locator field : visibleFields(page).all()) {
                assertInsideViewport(field, width);
            }
            screenshot(page, browser, width, "setup");
        }
    }

    private static List<String> watchRemoteKeys(Page page) {
        List<String> requests = new ArrayList<>();
        page.onRequest(request -> {
            if (request.url().matches(".*/devices/[^/]+/key/.*")) requests.add(request.url());
        });
        return requests;
    }

    private static Locator visibleFields(Page page) {
        return page.locator("input:not([type='hidden']):visible, select:visible, textarea:visible");
    }

    private static void assertNoPageOverflow(Page page) {
        Number overflow = (Number) page.evaluate("""
                () => Math.max(document.documentElement.scrollWidth, document.body.scrollWidth)
                    - document.documentElement.clientWidth
                """);
        Object overflowing = overflow.doubleValue() > 1 ? page.evaluate("""
                () => [...document.querySelectorAll('body *')]
                    .filter(el => el.getBoundingClientRect().right > innerWidth + 1)
                    .map(el => ({tag: el.tagName, id: el.id, cls: el.className,
                        right: el.getBoundingClientRect().right, width: el.getBoundingClientRect().width}))
                    .slice(0, 20)
                """) : List.of();
        org.assertj.core.api.Assertions.assertThat(overflow.doubleValue())
                .as("Horizontal page overflow at %s: %s", page.url(), overflowing).isLessThanOrEqualTo(1);
    }

    private static void assertInsideViewport(Locator element, int viewportWidth) {
        BoundingBox box = element.boundingBox();
        org.assertj.core.api.Assertions.assertThat(box).as("Visible control has bounds: %s", element).isNotNull();
        org.assertj.core.api.Assertions.assertThat(box.x).as("Control begins inside viewport: %s", element)
                .isGreaterThanOrEqualTo(-1);
        org.assertj.core.api.Assertions.assertThat(box.x + box.width).as("Control ends inside viewport: %s", element)
                .isLessThanOrEqualTo(viewportWidth + 1);
    }

    private static void screenshot(Page page, String browser, int width, String view) {
        Path path = Path.of(System.getProperty("e2e.artifacts", "build/e2e-artifacts"),
                "ui-" + view + "-" + width + "-" + browser + ".png");
        page.screenshot(new Page.ScreenshotOptions().setPath(path).setFullPage(true)
                .setAnimations(ScreenshotAnimations.DISABLED));
        if (view.equals("setup") || view.equals("first-run")) {
            page.screenshot(new Page.ScreenshotOptions().setPath(path.resolveSibling(
                    "ui-" + view + "-" + width + "-" + browser + "-viewport.png"))
                    .setAnimations(ScreenshotAnimations.DISABLED));
        }
    }
}

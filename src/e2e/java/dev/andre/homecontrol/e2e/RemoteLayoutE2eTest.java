package dev.andre.homecontrol.e2e;

import com.google.gson.JsonObject;
import com.microsoft.playwright.CDPSession;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.BoundingBox;
import dev.andre.homecontrol.core.DeviceState;
import org.junit.jupiter.api.Test;
import org.assertj.core.data.Offset;

import java.nio.file.Path;
import java.util.Map;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class RemoteLayoutE2eTest extends E2eApplicationTest {

    @BrowserTest
    void phoneRemoteOpensAboveThePageAndKeepsFocusAndScrollingInside(String browser) {
        try (BrowserSession session = Browsers.open(browser, baseUrl(), traceName, true)) {
            Page page = session.page();
            page.navigate("/?device=living");
            page.locator(".drawer-toggle").click();
            Locator drawer = page.locator("#remote-drawer");
            org.assertj.core.api.Assertions.assertThat(drawer.evaluate("el => el.matches(':modal')"))
                    .as("The phone remote is an overlay above the page").isEqualTo(true);
            assertInViewport(drawer, 390, 844);
            org.assertj.core.api.Assertions.assertThat(page.evaluate("() => getComputedStyle(document.body).overflowY"))
                    .as("Background scrolling is disabled while the overlay is open").isEqualTo("hidden");
            page.screenshot(new Page.ScreenshotOptions().setPath(Path.of(
                    System.getProperty("e2e.artifacts", "build/e2e-artifacts"), "remote-mobile-" + browser + ".png")));
            page.keyboard().press("Shift+Tab");
            org.assertj.core.api.Assertions.assertThat(drawer.evaluate("el => el.contains(document.activeElement)"))
                    .as("Focus cannot escape into the obscured page").isEqualTo(true);
            page.keyboard().press("Escape");
            assertThat(drawer).isHidden();
            assertThat(page.locator(".drawer-toggle")).isFocused();
            org.assertj.core.api.Assertions.assertThat(page.evaluate("() => getComputedStyle(document.body).overflowY"))
                    .as("Closing the overlay restores page scrolling").isNotEqualTo("hidden");

            page.locator(".drawer-toggle").click();
            drawer.evaluate("el => el.close()");
            assertThat(drawer).isHidden();
            assertThat(page.locator(".drawer-toggle")).hasAttribute("aria-expanded", "false");
            assertThat(page.locator(".drawer-toggle")).isFocused();
            page.locator(".devices").evaluate("el => el.scrollLeft = el.scrollWidth");
            org.assertj.core.api.Assertions.assertThat(((Number) page.locator(".devices")
                    .evaluate("el => el.scrollLeft")).doubleValue())
                    .as("The device strip still scrolls horizontally after closing the overlay").isPositive();
            page.setViewportSize(320, 568);
            Locator railTiles = page.locator("#rails .tiles").first();
            assertThat(railTiles.locator(".tile")).hasCount(2);
            railTiles.evaluate("el => el.scrollLeft = el.scrollWidth");
            org.assertj.core.api.Assertions.assertThat(((Number) railTiles.evaluate("el => el.scrollLeft")).doubleValue())
                    .as("Content rails still scroll horizontally after closing the overlay").isPositive();
            assertNoHorizontalOverflow(page);
        }
    }

    @BrowserTest
    void disconnectedTouchpadErrorsAreVisibleAboveTheMobileOverlay(String browser) {
        try (BrowserSession session = Browsers.open(browser, baseUrl(), traceName, true)) {
            Page page = session.page();
            page.navigate("/?device=living&remote=open");
            page.locator("[data-mode-switch] [data-mode=touchpad]").click();
            fakeDevices.push("living", DeviceState.initial());
            assertThat(page.locator("#touchpad")).hasAttribute("aria-disabled", "true");
            // A real touch can still hit an aria-disabled surface to explain why it is unavailable.
            BoundingBox pad = page.locator("#touchpad").boundingBox();
            page.touchscreen().tap(pad.x + pad.width / 2, pad.y + pad.height / 2);
            Locator toast = page.locator("#toast");
            assertThat(toast).containsText("The device is not connected");
            org.assertj.core.api.Assertions.assertThat(toast.evaluate("""
                    el => {
                        const bounds = el.getBoundingClientRect();
                        return el.contains(document.elementFromPoint(
                            bounds.x + bounds.width / 2, bounds.y + bounds.height / 2));
                    }
                    """)).as("The error is above the remote, not behind its modal backdrop").isEqualTo(true);
        }
    }

    @BrowserTest
    void remoteStaysInsideTheViewportWhileBrowsingAndResizing(String browser) {
        try (BrowserSession session = Browsers.open(browser, baseUrl(), traceName, true)) {
            Page page = session.page();
            page.navigate("/?device=living");
            page.locator(".drawer-toggle").click();
            Locator drawer = page.locator("#remote-drawer");

            for (int[] size : new int[][] {{320, 568}, {390, 844}, {768, 1024}, {844, 390}, {1440, 900}, {390, 844}}) {
                page.setViewportSize(size[0], size[1]);
                page.waitForFunction("() => document.getElementById('remote-drawer').matches(':modal')");
                for (String mode : new String[] {"buttons", "touchpad"}) {
                    page.locator("[data-mode-switch] [data-mode=" + mode + "]").click();
                    assertInViewport(drawer, size[0], size[1]);
                    org.assertj.core.api.Assertions.assertThat(drawer.boundingBox().width)
                            .as("Remote stays compact at %sx%s", size[0], size[1])
                            .isLessThanOrEqualTo(size[0] < 1024 && size[0] > size[1] ? 640 : 400);
                    double top = drawer.boundingBox().y;
                    page.evaluate("() => window.scrollTo(0, document.body.scrollHeight)");
                    assertInViewport(drawer, size[0], size[1]);
                    org.assertj.core.api.Assertions.assertThat(drawer.boundingBox().y)
                            .as("Page scrolling does not move the active remote")
                            .isCloseTo(top, Offset.offset(1.0));
                    assertNoHorizontalOverflow(page);
                }
            }
        }
    }

    @BrowserTest
    void wideTouchScreensKeepTheRemoteModalAndInsideThePage(String browser) {
        try (BrowserSession session = Browsers.open(browser, baseUrl(), traceName, true)) {
            Page page = session.page();
            for (int width : new int[] {1080, 1280}) {
                page.setViewportSize(width, 844);
                page.navigate("/?device=living");
                page.locator(".drawer-toggle").click();
                Locator drawer = page.locator("#remote-drawer");
                org.assertj.core.api.Assertions.assertThat(drawer.evaluate("el => el.matches(':modal')"))
                        .as("Touch device at %spx uses an overlay, not a desktop dock", width).isEqualTo(true);
                assertInViewport(drawer, width, 844);
                assertNoHorizontalOverflow(page);
                page.locator(".drawer-close").click();
                assertThat(drawer).isHidden();
            }
        }
    }

    @BrowserTest
    void finePointerDesktopStillUsesTheRightDock(String browser) {
        try (BrowserSession session = Browsers.openDesktop(browser, baseUrl(), traceName)) {
            Page page = session.page();
            page.setViewportSize(1440, 900);
            page.navigate("/?device=living");
            page.locator(".drawer-toggle").click();
            Locator drawer = page.locator("#remote-drawer");
            org.assertj.core.api.Assertions.assertThat(drawer.evaluate("el => el.matches(':modal')"))
                    .as("Fine-pointer desktop retains the right dock").isEqualTo(false);
            BoundingBox bounds = drawer.boundingBox();
            org.assertj.core.api.Assertions.assertThat(bounds.width).isCloseTo(400.0, Offset.offset(1.0));
            org.assertj.core.api.Assertions.assertThat(bounds.x + bounds.width)
                    .isCloseTo(1440.0, Offset.offset(1.0));
            assertNoHorizontalOverflow(page);
        }
    }

    @BrowserTest
    void finePointerResizeKeepsTheModeAndFocusAcrossModalAndDock(String browser) {
        try (BrowserSession session = Browsers.openDesktop(browser, baseUrl(), traceName)) {
            Page page = session.page();
            page.navigate("/?device=living");
            page.locator(".drawer-toggle").click();
            Locator drawer = page.locator("#remote-drawer");
            org.assertj.core.api.Assertions.assertThat(drawer.evaluate("el => el.matches(':modal')")).isEqualTo(true);
            page.locator("[data-mode-switch] [data-mode=touchpad]").click();
            Locator close = page.locator(".drawer-close");
            close.focus();

            page.setViewportSize(1440, 900);
            page.waitForFunction("() => !document.getElementById('remote-drawer').matches(':modal')");
            assertInViewport(drawer, 1440, 900);
            org.assertj.core.api.Assertions.assertThat(drawer.boundingBox().x + drawer.boundingBox().width)
                    .isCloseTo(1440.0, Offset.offset(1.0));
            assertThat(page.locator("[data-mode-switch] [data-mode=touchpad]")).hasAttribute("aria-selected", "true");
            assertThat(close).isFocused();

            page.setViewportSize(390, 844);
            page.waitForFunction("() => document.getElementById('remote-drawer').matches(':modal')");
            assertInViewport(drawer, 390, 844);
            assertThat(page.locator("[data-mode-switch] [data-mode=touchpad]")).hasAttribute("aria-selected", "true");
            assertThat(close).isFocused();
            close.click();
            assertThat(drawer).isHidden();
            assertThat(page.locator(".drawer-toggle")).isFocused();
            assertNoHorizontalOverflow(page);
        }
    }

    @Test
    void chromiumPinchZoomWhileOpenKeepsTheRemoteInsideTheVisualViewport() {
        assumeTrue(Browsers.names().anyMatch("chromium"::equals), "Chromium is not selected for this run");
        try (BrowserSession session = Browsers.open("chromium", baseUrl(), traceName, true)) {
            Page page = session.page();
            page.navigate("/?device=living&remote=open");
            CDPSession cdp = session.context().newCDPSession(page);
            try {
                setPageScale(cdp, 2);
                page.waitForFunction("() => visualViewport.scale >= 1.99");
                assertInVisualViewport(page);
                page.evaluate("""
                        async () => {
                            const { toast } = await import('/js/toast.js');
                            toast('Zoomed remote error', { duration: 30000 });
                        }
                        """);
                assertThat(page.locator("#toast")).containsText("Zoomed remote error");
                assertElementInVisualViewport(page, "#toast");
                assertNoHorizontalOverflow(page);

                setPageScale(cdp, 1);
                page.waitForFunction("() => visualViewport.scale <= 1.01");
                assertInVisualViewport(page);
                assertElementInVisualViewport(page, "#toast");
                assertNoHorizontalOverflow(page);
            } finally {
                cdp.detach();
            }
        }
    }

    @Test
    void chromiumPinchZoomBeforeOpeningKeepsTheRemoteInsideTheVisualViewport() {
        assumeTrue(Browsers.names().anyMatch("chromium"::equals), "Chromium is not selected for this run");
        try (BrowserSession session = Browsers.open("chromium", baseUrl(), traceName, true)) {
            Page page = session.page();
            page.navigate("/?device=living");
            CDPSession cdp = session.context().newCDPSession(page);
            try {
                setPageScale(cdp, 2);
                page.waitForFunction("() => visualViewport.scale >= 1.99");
                page.locator(".drawer-toggle").focus();
                page.keyboard().press("Enter");
                org.assertj.core.api.Assertions.assertThat(page.locator("#remote-drawer")
                        .evaluate("el => el.matches(':modal')")).isEqualTo(true);
                assertInVisualViewport(page);
                assertNoHorizontalOverflow(page);
            } finally {
                cdp.detach();
            }
        }
    }

    @BrowserTest
    void remoteHeaderAndCloseStayVisibleWhenItsControlsScroll(String browser) {
        try (BrowserSession session = Browsers.open(browser, baseUrl(), traceName, true)) {
            Page page = session.page();
            page.setViewportSize(667, 375);
            page.navigate("/?device=bedroom&remote=open");
            Locator drawer = page.locator("#remote-drawer");
            assertInViewport(drawer, 667, 375);
            drawer.evaluate("el => el.scrollTop = el.scrollHeight");
            org.assertj.core.api.Assertions.assertThat(((Number) drawer.evaluate("el => el.scrollTop")).doubleValue())
                    .as("The remote contains enough controls to scroll").isPositive();

            assertInViewport(page.locator(".drawer-header"), 667, 375);
            assertInViewport(page.locator(".drawer-close"), 667, 375);
            // Check bounds before clicking so Playwright cannot scroll the button into view.
            page.locator(".drawer-close").click();
            assertThat(drawer).isHidden();
            assertThat(page.locator(".drawer-toggle")).isFocused();
        }
    }

    @BrowserTest
    void longDeviceNamesDoNotWidenTheRemoteOrHideItsCloseButton(String browser) {
        adopt("living", "LivingRoomTelevisionWithAnUnbrokenDeviceName", "APP_LINK,REMOTE_KEYS", "");
        try (BrowserSession session = Browsers.open(browser, baseUrl(), traceName, true)) {
            Page page = session.page();
            page.setViewportSize(320, 568);
            page.navigate("/?device=living&remote=open");
            assertInViewport(page.locator("#remote-drawer"), 320, 568);
            assertInViewport(page.locator(".drawer-close"), 320, 568);
            assertNoHorizontalOverflow(page);
        }
    }

    private static void assertInViewport(Locator element, int width, int height) {
        BoundingBox bounds = element.boundingBox();
        org.assertj.core.api.Assertions.assertThat(bounds).as("Visible bounds for %s", element).isNotNull();
        org.assertj.core.api.Assertions.assertThat(bounds.x).as("Left edge of %s", element).isGreaterThanOrEqualTo(-1);
        org.assertj.core.api.Assertions.assertThat(bounds.y).as("Top edge of %s", element).isGreaterThanOrEqualTo(-1);
        org.assertj.core.api.Assertions.assertThat(bounds.x + bounds.width).as("Right edge of %s", element)
                .isLessThanOrEqualTo(width + 1);
        org.assertj.core.api.Assertions.assertThat(bounds.y + bounds.height).as("Bottom edge of %s", element)
                .isLessThanOrEqualTo(height + 1);
    }

    private static void setPageScale(CDPSession cdp, double scale) {
        JsonObject parameters = new JsonObject();
        parameters.addProperty("pageScaleFactor", scale);
        cdp.send("Emulation.setPageScaleFactor", parameters);
    }

    private static void assertInVisualViewport(Page page) {
        assertElementInVisualViewport(page, "#remote-drawer");
        assertElementInVisualViewport(page, ".drawer-close");
    }

    private static void assertElementInVisualViewport(Page page, String selector) {
        page.evaluate("() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))");
        Map<?, ?> bounds = (Map<?, ?>) page.evaluate("""
                selector => {
                    const visual = window.visualViewport;
                    const element = document.querySelector(selector).getBoundingClientRect();
                    return {
                        left: element.left, right: element.right, top: element.top, bottom: element.bottom,
                        visualLeft: visual.offsetLeft, visualRight: visual.offsetLeft + visual.width,
                        visualTop: visual.offsetTop, visualBottom: visual.offsetTop + visual.height
                    };
                }
                """, selector);
        double left = ((Number) bounds.get("visualLeft")).doubleValue();
        double right = ((Number) bounds.get("visualRight")).doubleValue();
        double top = ((Number) bounds.get("visualTop")).doubleValue();
        double bottom = ((Number) bounds.get("visualBottom")).doubleValue();
        org.assertj.core.api.Assertions.assertThat(((Number) bounds.get("left")).doubleValue())
                .as("%s left edge must fit visual viewport: %s", selector, bounds).isGreaterThanOrEqualTo(left - 1);
        org.assertj.core.api.Assertions.assertThat(((Number) bounds.get("right")).doubleValue())
                .as("%s right edge must fit visual viewport: %s", selector, bounds).isLessThanOrEqualTo(right + 1);
        org.assertj.core.api.Assertions.assertThat(((Number) bounds.get("top")).doubleValue())
                .as("%s top edge must fit visual viewport: %s", selector, bounds).isGreaterThanOrEqualTo(top - 1);
        org.assertj.core.api.Assertions.assertThat(((Number) bounds.get("bottom")).doubleValue())
                .as("%s bottom edge must fit visual viewport: %s", selector, bounds).isLessThanOrEqualTo(bottom + 1);
    }

    private static void assertNoHorizontalOverflow(Page page) {
        for (String selector : new String[] {"html", "body", "#remote-drawer"}) {
            Number overflow = (Number) page.locator(selector).evaluate("el => el.scrollWidth - el.clientWidth");
            org.assertj.core.api.Assertions.assertThat(overflow.doubleValue())
                    .as("Horizontal overflow in %s", selector).isLessThanOrEqualTo(1);
        }
    }
}

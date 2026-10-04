package dev.andre.homecontrol.e2e;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.Route;
import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.DeviceStatus;

import java.time.Instant;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * What the play sheet says when the server refuses, cannot be reached, or knows no way to open an item; and that a
 * link the device did open is not second-guessed. Nothing here pins a link, so the class shares one context.
 */
class PlaySheetFailureE2eTest extends E2eApplicationTest {

    private static final String JSON = "application/json";

    private static void openFromSearch(Page page, String query, String itemId) {
        page.navigate("/?device=living");
        page.locator("#search-q").fill(query);
        page.locator("#search-results .tile[data-item='" + itemId + "']").click();
    }

    @BrowserTest
    void aPreviewTheServerRefusesShowsItsReason(String browser) {
        try (BrowserSession session = open(browser)) {
            Page page = session.page();
            page.route("**/route-preview?*", route -> route.fulfill(new Route.FulfillOptions().setStatus(503)
                    .setContentType(JSON).setBody("{\"message\":\"Living Room is restarting\"}")));
            page.navigate("/?device=living");

            page.locator("button.tile[data-item='clip-1']").click();

            assertThat(page.locator("#sheet-route")).hasText("Living Room is restarting");
            assertThat(page.locator("#sheet-route")).hasClass(java.util.regex.Pattern.compile("unroutable"));
            assertThat(page.locator("#sheet-play")).isDisabled();
        }
    }

    @BrowserTest
    void aPlayThatCannotReachTheServerSaysSoAndCanBeTriedAgain(String browser) {
        try (BrowserSession session = open(browser)) {
            Page page = session.page();
            page.route("**/play-attempt", Route::abort);
            page.navigate("/?device=living");
            page.locator("button.tile[data-item='clip-1']").click();
            assertThat(page.locator("#sheet-play")).isEnabled();

            page.locator("#sheet-play").click();

            assertThat(page.locator("#toast .toast-text")).hasText("Cannot reach the server");
            assertThat(page.locator("#play-sheet")).not().hasAttribute("open", "");
            assertThat(page.locator("#sheet-play")).isEnabled();
            org.assertj.core.api.Assertions.assertThat(fakeDevices.recorded("living")).isEmpty();
        }
    }

    @BrowserTest
    void aRefusalWithoutARouteShowsTheServersMessage(String browser) {
        try (BrowserSession session = open(browser)) {
            Page page = session.page();
            page.route("**/play-attempt", route -> route.fulfill(new Route.FulfillOptions().setStatus(409)
                    .setContentType(JSON).setBody("{\"played\":false,\"message\":\"Living Room is busy\"}")));
            page.navigate("/?device=living");
            page.locator("button.tile[data-item='clip-1']").click();

            page.locator("#sheet-play").click();

            assertThat(page.locator("#toast .toast-text")).hasText("Living Room is busy");
            assertThat(page.locator("#play-sheet")).not().hasAttribute("open", "");
            assertThat(page.locator("#sheet-play")).isEnabled();
        }
    }

    @BrowserTest
    void anAppLinkThatVisiblyStartedSomethingIsNotSecondGuessed(String browser) {
        try (BrowserSession session = open(browser)) {
            Page page = session.page();
            page.clock().install();
            page.navigate("/?device=living");
            page.locator("button.tile[data-item='clip-1']").click();
            page.locator("#sheet-play").click();
            assertThat(page.locator("#toast")).containsText("on Living Room");

            fakeDevices.push("living", new DeviceState(DeviceStatus.CONNECTED, true, "com.google.android.youtube.tv",
                    0, 0, false, Instant.now()));
            assertThat(page.locator("#app-living")).hasText("com.google.android.youtube.tv");
            page.clock().runFor(5_100);

            assertThat(page.locator("#toast .toast-text")).not().containsText("may not be installed");
        }
    }

    @BrowserTest
    void aTitleNoServiceOpensOffersToPinAnyLink(String browser) {
        try (BrowserSession session = open(browser)) {
            Page page = session.page();

            openFromSearch(page, "Indie", "unlinked-1");

            assertThat(page.locator("#sheet-route")).containsText("Cannot play on Living Room");
            assertThat(page.locator("#sheet-play")).isDisabled();
            assertThat(page.locator("#sheet-pin-text")).hasText("Home Control cannot open this title on your services. "
                    + "Paste a link to it (Netflix, Prime Video, YouTube, DAZN or any web link) to pin it.");
        }
    }

    @BrowserTest
    void anEventNoServiceOpensOffersToPinItsPage(String browser) {
        try (BrowserSession session = open(browser)) {
            Page page = session.page();

            openFromSearch(page, "Cup Final", "event-1");

            assertThat(page.locator("#sheet-pin-text")).hasText("Home Control cannot open this event directly. Paste a "
                    + "link to it (for example the event's page on your streaming service) to pin it.");
        }
    }

    @BrowserTest
    void pinningALinkThatCannotReachTheServerSaysSoInTheSheet(String browser) {
        try (BrowserSession session = open(browser)) {
            Page page = session.page();
            page.route("**/setup/sources/pinned/upgrade", Route::abort);
            openFromSearch(page, "Launcher", "launcher-1");
            assertThat(page.locator("#sheet-pin")).isVisible();

            page.locator("#sheet-pin-url").fill("https://www.netflix.com/title/80057281");
            page.locator("#sheet-pin-submit").click();

            assertThat(page.locator("#sheet-pin-error")).hasText("Cannot reach the server");
            assertThat(page.locator("#sheet-pin-submit")).isEnabled();
            assertThat(page.locator("#play-sheet")).hasAttribute("open", "");
        }
    }
}

package dev.andre.homecontrol.e2e;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import dev.andre.homecontrol.core.Action;

import java.util.regex.Pattern;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.awaitility.Awaitility.await;

class PlaySheetE2eTest extends E2eApplicationTest {

    @BrowserTest
    void showsThePlannedRouteBeforePlayingAndPlaysWithOneTap(String browser) {
        try (BrowserSession session = open(browser)) {
            Page page = session.page();
            page.navigate("/?device=living");
            page.locator("button.tile[data-item='clip-1']").click();

            assertThat(page.locator("#sheet-title")).hasText("Big Buck Bunny");
            assertThat(page.locator("#sheet-route")).hasText("Play on Living Room · Open in the YouTube app");
            org.assertj.core.api.Assertions.assertThat(fakeDevices.recorded("living")).isEmpty();

            page.locator("#sheet-play").click();

            await().until(() -> fakeDevices.recorded("living").stream().anyMatch(Action.OpenAppLink.class::isInstance));
            assertThat(page.locator("#toast")).containsText("Open in the YouTube app on Living Room");
            assertThat(page.locator("#play-sheet")).not().hasAttribute("open", "");
        }
    }

    @BrowserTest
    void hintsThatTheAppMayNotBeInstalledWhenNothingChanges(String browser) {
        try (BrowserSession session = open(browser)) {
            Page page = session.page();
            page.clock().install();
            page.navigate("/?device=living");
            page.locator("button.tile[data-item='clip-1']").click();
            page.locator("#sheet-play").click();
            assertThat(page.locator("#toast")).containsText("on Living Room");

            page.clock().runFor(5_100);

            assertThat(page.locator("#toast .toast-text"))
                    .hasText("If nothing started on Living Room, the app for this link may not be installed.");
        }
    }

    @BrowserTest
    void aFailureNamesTheFailedRouteAndOffersTheNextOne(String browser) {
        try (BrowserSession session = open(browser)) {
            Page page = session.page();
            page.navigate("/?device=bedroom");
            page.locator("button.tile[data-item='clip-1']").click();
            assertThat(page.locator("#sheet-route")).hasText("Play on Bedroom · Open in the YouTube app");

            page.locator("#sheet-play").click();

            assertThat(page.locator("#toast .toast-text")).hasText(Pattern.compile(
                    "^Open in the YouTube app failed: Bedroom refused to OpenAppLink\\. Next: Cast with the Default Media Receiver$"));
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Try Cast with the Default Media Receiver")).click();

            await().until(() -> fakeDevices.recorded("bedroom").stream().anyMatch(Action.CastLoad.class::isInstance));
            assertThat(page.locator("#toast")).containsText("Cast with the Default Media Receiver on Bedroom");
        }
    }

    @BrowserTest
    void anUnroutableDeviceExplainsWhyAndCannotPlay(String browser) {
        try (BrowserSession session = open(browser)) {
            Page page = session.page();
            page.navigate("/?device=speaker");
            page.locator("button.tile[data-item='clip-2']").click();

            assertThat(page.locator("#sheet-route")).containsText("Cannot play on Speaker");
            assertThat(page.locator("#sheet-play")).isDisabled();
        }
    }
}

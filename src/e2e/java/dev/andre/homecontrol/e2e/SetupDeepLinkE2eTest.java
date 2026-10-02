package dev.andre.homecontrol.e2e;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Route;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/** The deep-link test shows why it could not run under its button; the setup page's script says it, not the markup. */
class SetupDeepLinkE2eTest extends E2eApplicationTest {

    private static final String LIVING_ROOM_TEST = "button.deep-link-test[hx-post$='/setup/devices/living/deep-link-test']";

    @BrowserTest
    void aDeviceForgottenSinceThePageLoadedShowsWhyTheTestCouldNotRun(String browser) {
        try (BrowserSession session = open(browser)) {
            Page page = session.page();
            page.navigate("/setup");
            Locator button = page.locator(LIVING_ROOM_TEST);
            assertThat(button).isVisible();

            enrollment.forget("living");
            button.click();

            assertThat(page.locator(LIVING_ROOM_TEST + " + .deep-link-output")).hasText("No device with id living");
        }
    }

    @BrowserTest
    void aTestThatCannotReachTheServerSaysSoUnderItsButton(String browser) {
        try (BrowserSession session = open(browser)) {
            Page page = session.page();
            page.navigate("/setup");
            Locator button = page.locator(LIVING_ROOM_TEST);
            assertThat(button).isVisible();

            page.route("**/setup/devices/living/deep-link-test", Route::abort);
            button.click();

            assertThat(page.locator(LIVING_ROOM_TEST + " + .deep-link-output")).hasText("Cannot reach the server");
        }
    }
}

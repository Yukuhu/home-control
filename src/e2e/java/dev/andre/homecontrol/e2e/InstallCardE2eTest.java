package dev.andre.homecontrol.e2e;

import com.microsoft.playwright.Page;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * The setup page's install card follows what the browser can do: its own install prompt, Safari's Add to Home
 * Screen, or an app that is already installed. The browser's side is simulated, as no headless browser installs.
 */
class InstallCardE2eTest extends E2eApplicationTest {

    @BrowserTest
    void aBrowserThatOffersInstallingGetsAButtonThatPromptsOnce(String browser) {
        try (BrowserSession session = open(browser)) {
            Page page = session.page();
            page.navigate("/setup#install");
            assertThat(page.locator("#install-http-note")).isVisible();
            assertThat(page.locator("#install-button")).isHidden();

            page.evaluate("""
                    () => {
                        const offer = new Event('beforeinstallprompt', { cancelable: true });
                        offer.prompt = () => { window.installPrompts = (window.installPrompts || 0) + 1; };
                        offer.userChoice = Promise.resolve({ outcome: 'accepted' });
                        window.dispatchEvent(offer);
                        window.installOfferPrevented = offer.defaultPrevented;
                    }
                    """);
            assertThat(page.locator("#install-button")).isVisible();
            org.assertj.core.api.Assertions.assertThat(page.evaluate("window.installOfferPrevented")).isEqualTo(true);

            page.locator("#install-button").click();

            assertThat(page.locator("#install-button")).isHidden();
            org.assertj.core.api.Assertions.assertThat(page.evaluate("window.installPrompts")).isEqualTo(1);
        }
    }

    @BrowserTest
    void safariOnAnIphoneIsToldToAddToTheHomeScreen(String browser) {
        try (BrowserSession session = open(browser)) {
            session.context().addInitScript(
                    "Object.defineProperty(Navigator.prototype, 'standalone', { configurable: true, get: () => false })");
            Page page = session.page();

            page.navigate("/setup#install");

            assertThat(page.locator("#install-ios")).isVisible();
            assertThat(page.locator("#install-other")).isHidden();
            assertThat(page.locator("#install-done")).isHidden();
        }
    }

    @BrowserTest
    void anInstalledAppSaysSoAndOffersNothing(String browser) {
        try (BrowserSession session = open(browser)) {
            session.context().addInitScript(
                    "Object.defineProperty(Navigator.prototype, 'standalone', { configurable: true, get: () => true })");
            Page page = session.page();

            page.navigate("/setup#install");

            assertThat(page.locator("#install-done")).isVisible();
            assertThat(page.locator("#install-ios")).isHidden();
            assertThat(page.locator("#install-other")).isHidden();
            assertThat(page.locator("#install-http-note")).isHidden();
        }
    }
}

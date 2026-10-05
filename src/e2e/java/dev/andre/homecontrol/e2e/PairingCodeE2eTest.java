package dev.andre.homecontrol.e2e;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.regex.Pattern;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

/** A pairing code is good for one try: once the form is sent, its button is disabled. */
class PairingCodeE2eTest extends E2eApplicationTest {

    @Autowired
    private FakeCodePairing codePairing;

    @BrowserTest
    void thePairButtonSendsTheCodeOnce(String browser) {
        codePairing.awaitCode();
        try (BrowserSession session = open(browser)) {
            Page page = session.page();
            page.navigate("/setup");
            page.getByLabel("Pairing code").fill("A1B2C3");
            Locator pair = page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Pair device"));

            // Read in the click's own task: Playwright waits out the held answer before it looks at the old page.
            Object disabledOnceSent = pair.evaluate("button => { button.click(); return button.disabled; }");

            assertThat(disabledOnceSent).isEqualTo(true);
            codePairing.answer();
            assertThat(page).hasURL(Pattern.compile(".*/$"));
            assertThat(codePairing.submits()).isEqualTo(1);
        } finally {
            codePairing.reset();
        }
    }

    @BrowserTest
    void aPairingFormTheBrowserBringsBackFromItsCacheCanBeSentAgain(String browser) {
        try (BrowserSession session = open(browser)) {
            Page page = session.page();
            page.navigate("/setup");
            // The page stays: the form's own submit listener runs, the navigation is held back.
            page.evaluate("document.addEventListener('submit', event => event.preventDefault())");
            page.getByText("Add an Android TV by address").click();
            Locator form = page.locator("form[action='/setup/pair']");
            form.getByLabel("IP address").fill("192.0.2.10");
            Locator pair = form.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Pair device"));

            pair.click();
            assertThat(pair).isDisabled();

            // What Back to a page from the back-forward cache fires.
            page.evaluate("window.dispatchEvent(new PageTransitionEvent('pageshow', { persisted: true }))");
            assertThat(pair).isEnabled();
        }
    }
}

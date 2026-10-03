package dev.andre.homecontrol.e2e;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.regex.Pattern;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

/** A pairing code is good for one try: the form sends it once, however often its button is pressed. */
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
            codePairing.answer();
        }
    }
}

package dev.andre.homecontrol.e2e;

import com.microsoft.playwright.Page;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The pages' Content-Security-Policy blocks script that did not come from this server, and every browser test's
 * session reports such a block, so a page that grows inline script again fails the browser tests.
 */
class ContentSecurityPolicyE2eTest extends E2eApplicationTest {

    @BrowserTest
    void anInjectedInlineScriptIsBlockedAndReported(String browser) {
        try (BrowserSession session = open(browser)) {
            Page page = session.page();
            page.navigate("/");

            page.evaluate("""
                    () => {
                        const script = document.createElement("script");
                        script.textContent = "window.injectedScriptRan = true";
                        document.body.append(script);
                    }""");
            page.waitForCondition(() -> !session.cspViolations().isEmpty());

            assertThat(page.evaluate("() => window.injectedScriptRan === true")).isEqualTo(false);
            assertThat(session.cspViolations()).singleElement().asString().startsWith("script-src");
            session.cspViolations().clear();
        }
    }
}

package dev.andre.homecontrol.e2e;

import com.microsoft.playwright.Page;
import dev.andre.homecontrol.testsupport.FakeHttpServer;
import dev.andre.homecontrol.testsupport.Response;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

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

            injectInlineScript(page);
            page.waitForCondition(() -> !session.cspViolations().isEmpty());

            assertThat(page.evaluate("() => window.injectedScriptRan === true")).isEqualTo(false);
            assertThat(session.cspViolations()).singleElement().asString().startsWith("script-src");
            session.cspViolations().clear();
        }
    }

    @BrowserTest
    void aSessionThatSawABlockFailsWhenItCloses(String browser) {
        AssertionError failure = catchThrowableOfType(AssertionError.class, () -> {
            try (BrowserSession session = open(browser)) {
                Page page = session.page();
                page.navigate("/");
                injectInlineScript(page);
                page.waitForCondition(() -> !session.cspViolations().isEmpty());
            }
        });

        assertThat(failure).hasMessageStartingWith("The Content-Security-Policy blocked [script-src");
    }

    @BrowserTest
    void aBlockInsideAServiceWorkerFailsTheSession(String browser) throws IOException {
        assumeFalse(browser.equals("webkit"), "WebKit reports no Content-Security-Policy block inside a service worker");
        String policy = "default-src 'self'; connect-src 'self'; worker-src 'self'";
        try (FakeHttpServer origin = FakeHttpServer.start()) {
            origin.respond("GET", "/", Response.of(200, "text/html", "<!doctype html><title>Worker</title>")
                    .withHeader("Content-Security-Policy", policy));
            origin.respond("GET", "/worker.js", Response.of(200, "text/javascript", Browsers.reportingCspViolations("""
                    self.addEventListener("install", () => self.skipWaiting());
                    self.addEventListener("activate", (event) => event.waitUntil(self.clients.claim()));
                    self.addEventListener("message", () => fetch("http://127.0.0.2:9/elsewhere").catch(() => {}));
                    """)).withHeader("Content-Security-Policy", policy));

            AssertionError failure = catchThrowableOfType(AssertionError.class, () -> {
                try (BrowserSession session = Browsers.open(browser, origin.url().toString(), traceName)) {
                    Page page = session.page();
                    page.navigate("/");
                    page.evaluate("() => navigator.serviceWorker.register('/worker.js')");
                    page.waitForFunction("() => Boolean(navigator.serviceWorker.controller)");
                    page.evaluate("() => navigator.serviceWorker.controller.postMessage('fetch')");
                    page.waitForCondition(() -> !session.cspViolations().isEmpty());
                }
            });

            assertThat(failure).hasMessageContaining("connect-src http://127.0.0.2:9/elsewhere")
                    .hasMessageContaining("in the service worker");
        }
    }

    private static void injectInlineScript(Page page) {
        page.evaluate("""
                () => {
                    const script = document.createElement("script");
                    script.textContent = "window.injectedScriptRan = true";
                    document.body.append(script);
                }""");
    }
}

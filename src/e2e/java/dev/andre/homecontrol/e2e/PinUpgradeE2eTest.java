package dev.andre.homecontrol.e2e;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.Timeout;
import org.springframework.test.annotation.DirtiesContext;

import java.util.concurrent.TimeUnit;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * The play sheet's "paste a link to open this title directly" prompt (G4): a fake item that can
 * only open the Netflix app home is upgraded to a real title link, and a bad link is rejected
 * inline. {@code @DirtiesContext} after each method: pinning is stored in the shared data
 * directory {@link E2eApplicationTest#isolatedDataDirectory} creates once per class, and
 * {@code PinnedShortcuts} caches it in memory, so the pin from the first test would otherwise
 * leak into the second — a fresh context (and so a fresh temp directory) per method avoids that.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class PinUpgradeE2eTest extends E2eApplicationTest {

    private Page openWithLauncherSheet(BrowserSession session) {
        Page page = session.page();
        page.navigate("/?device=living");
        page.locator("#search-q").fill("Launcher");
        assertThat(page.locator("#search-results .tile[data-item='launcher-1']")).isVisible();
        page.locator("#search-results .tile[data-item='launcher-1']").click();
        return page;
    }

    /**
     * {@code @Timeout}: this interaction both submits a form via fetch and, from that fetch's
     * success handler, triggers a second async round trip (the re-preview) while a native
     * {@code <dialog>} is open. It used to hang for minutes at a time in this headless environment
     * (reproduced in both Chromium and WebKit) — not a browser-automation quirk after all, but
     * rails.js's {@code refetch()} recursing into an endless microtask loop whenever a second rail
     * refresh raced the first one's in-flight htmx request, starving the page's event loop. That is
     * fixed (rails.js now tracks each rail's in-flight request against its own element instead of
     * {@code document.body}, and only recurses once the element has actually been swapped out). The
     * {@code @Timeout} stays as a guard against any future regression, not because this is expected
     * to hang.
     */
    @BrowserTest
    @Timeout(value = 45, unit = TimeUnit.SECONDS)
    void pinningALinkUpgradesTheSheet(String browser) {
        try (BrowserSession session = open(browser)) {
            Page page = openWithLauncherSheet(session);

            assertThat(page.locator("#sheet-route")).hasText("Play on Living Room · Open the Netflix app (not this title)");
            assertThat(page.locator("#sheet-pin")).isVisible();
            assertThat(page.locator("#sheet-pin-text")).containsText("Paste the Netflix link for this title");

            page.locator("#sheet-pin-url").fill("https://www.netflix.com/de/title/80057281?s=a");
            page.locator("#sheet-pin-submit").click();

            assertThat(page.locator("#toast")).containsText("Pinned Launcher Film. It now opens directly.");
            assertThat(page.locator("#sheet-route")).hasText("Play on Living Room · Open in the Netflix app");
            assertThat(page.locator("#sheet-pin")).isHidden();

            page.locator("#play-sheet .sheet-close").click();
            Locator pinnedRail = page.locator(".rail[data-rail='pinned/pinned']");
            assertThat(pinnedRail.locator("h2")).hasText("Pinned");
            assertThat(pinnedRail).containsText("Launcher Film");
        }
    }

    @BrowserTest
    void aBadLinkShowsAnInlineError(String browser) {
        try (BrowserSession session = open(browser)) {
            Page page = openWithLauncherSheet(session);

            assertThat(page.locator("#sheet-route")).hasText("Play on Living Room · Open the Netflix app (not this title)");
            page.evaluate("document.getElementById('sheet-pin-url').type='text'");
            page.locator("#sheet-pin-url").fill("ftp://example.org/x");
            page.locator("#sheet-pin-submit").click();

            assertThat(page.locator("#sheet-pin-error")).isVisible();
            assertThat(page.locator("#sheet-pin-error")).hasText("Only http and https links can be opened on a device");
            assertThat(page.locator("#play-sheet")).hasAttribute("open", "");
        }
    }
}

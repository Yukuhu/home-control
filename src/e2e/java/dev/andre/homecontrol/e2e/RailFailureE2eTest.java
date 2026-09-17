package dev.andre.homecontrol.e2e;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.assertions.LocatorAssertions;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.BoundingBox;
import dev.andre.homecontrol.content.RailSnapshot;
import dev.andre.homecontrol.content.RailStatus;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Review finding: these tests used to all share the single "e2e/flaky" rail, so passing depended
 * on JUnit's (unspecified) method order carrying the right {@link dev.andre.homecontrol.content.RailCache}
 * state (versions, failure counts) from one test to the next rather than each test's own setup —
 * a fresh Spring context per method was tried and rejected: it reliably works in isolation but
 * this sandbox measured it flaky (10s, then even 15s and 30s Awaitility timeouts) once several
 * e2e classes' context churn piles up in one JVM. Each test below now drives its own independent
 * flaky rail id ({@link FakeContentSource#FLAKY_RAIL_IDS}) instead, so no two tests' break/heal
 * cycles or RailCache backoff state can ever interfere, with no context-recreation cost at all.
 */
class RailFailureE2eTest extends E2eApplicationTest {

    /**
     * Triggers a refresh of {@code railId} and waits for a snapshot strictly newer than the one
     * {@code refresh()} itself returned (the "still refreshing" marker, taken before the fetch it
     * starts even begins) to reach {@code status} — comparing the version, not just the status,
     * so this can never pass by observing a stale snapshot a previous refresh left behind.
     */
    private void refreshAndAwaitFlakyStatus(String railId, RailStatus status) {
        long triggeredVersion = rails.refresh("e2e", railId).map(RailSnapshot::version)
                .orElseThrow(() -> new IllegalStateException("No e2e/" + railId + " rail entry"));
        await().until(() -> rails.snapshot("e2e", railId)
                .filter(s -> s.version() > triggeredVersion)
                .map(s -> s.status() == status)
                .orElse(false));
    }

    @BrowserTest
    void aFailedRailShowsACompactErrorWithRetryNotAGap(String browser) {
        refreshAndAwaitFlakyStatus("flaky", RailStatus.FAILED);

        try (BrowserSession session = open(browser)) {
            Page page = session.page();
            page.navigate("/");

            Locator flaky = page.locator(".rail[data-rail='e2e/flaky']");
            assertThat(flaky).isVisible();
            assertThat(flaky).hasAttribute("data-status", "FAILED");
            assertThat(flaky).containsText("Couldn't load Flaky rail: E2E source is down");
            assertThat(flaky.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Retry"))).isVisible();

            BoundingBox box = flaky.boundingBox();
            org.assertj.core.api.Assertions.assertThat(box).isNotNull();
            org.assertj.core.api.Assertions.assertThat(box.height).isGreaterThanOrEqualTo(80);

            assertThat(page.locator(".rail[data-rail='e2e/picks'] .tiles .tile")).hasCount(2);
        }
    }

    @BrowserTest
    void retryRecoversTheRail(String browser) {
        refreshAndAwaitFlakyStatus("flaky-2", RailStatus.FAILED);

        try (BrowserSession session = open(browser)) {
            Page page = session.page();
            page.navigate("/");

            Locator flaky = page.locator(".rail[data-rail='e2e/flaky-2']");
            assertThat(flaky).hasAttribute("data-status", "FAILED");

            fakeContent.heal("flaky-2");
            flaky.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Retry")).click();

            // The POST returns the "refreshing" fragment; the final READY one only arrives after the
            // background fetch completes and the SSE `rail` event tells the page to re-fetch it.
            assertThat(flaky).hasAttribute("data-status", "READY", new LocatorAssertions.HasAttributeOptions().setTimeout(15_000));
            assertThat(flaky.locator(".tile")).hasCount(1);
            assertThat(flaky.locator(".tile[data-item='clip-2']")).isVisible();
        }
    }

    @BrowserTest
    void aRailThatBreaksWhileTheTabIsOpenUpdatesWithoutReload(String browser) {
        fakeContent.heal("flaky-3");
        refreshAndAwaitFlakyStatus("flaky-3", RailStatus.READY);

        try (BrowserSession session = open(browser)) {
            Page page = session.page();
            page.navigate("/");
            String url = page.url();

            Locator flaky = page.locator(".rail[data-rail='e2e/flaky-3']");
            assertThat(flaky.locator(".tile[data-item='clip-2']")).isVisible();

            fakeContent.breakFlaky("flaky-3");
            rails.refresh("e2e", "flaky-3");

            // Reaches the open tab purely through the SSE `rail` event; no navigation happens.
            assertThat(flaky.locator(".rail-stale"))
                    .hasText("Couldn't refresh", new LocatorAssertions.HasTextOptions().setTimeout(15_000));
            assertThat(flaky.locator(".tile[data-item='clip-2']")).isVisible();
            org.assertj.core.api.Assertions.assertThat(page.url()).isEqualTo(url);
        }
    }

    @BrowserTest
    void searchResultsOpenThePlaySheet(String browser) {
        try (BrowserSession session = open(browser)) {
            Page page = session.page();
            page.navigate("/");

            page.locator("#search-q").fill("bunny");
            assertThat(page.locator("#search-results .tile[data-item='clip-1']")).isVisible();
            assertThat(page.locator("#rails")).isHidden();

            page.locator("#search-results .tile[data-item='clip-1']").click();
            assertThat(page.locator("#sheet-title")).hasText("Big Buck Bunny");
            page.locator("#play-sheet .sheet-close").click();

            page.locator("#search-q").fill("");
            assertThat(page.locator("#rails")).isVisible();
        }
    }
}

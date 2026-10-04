package dev.andre.homecontrol.e2e;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import org.springframework.test.annotation.DirtiesContext;

import java.util.List;
import java.util.regex.Pattern;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * What the setup page changes on the dashboard, through its own forms: pinned links, the order and visibility of
 * rails, a source shown or hidden, and a password from first set to removed. Each test changes stored settings,
 * so each gets a fresh application and data directory.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class SetupFlowsE2eTest extends E2eApplicationTest {

    private static final String PASSWORD = "household-password-1";

    private static Locator button(Locator scope, String name) {
        return scope.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName(name).setExact(true));
    }

    @SuppressWarnings("unchecked")
    private static List<String> railsOnTheDashboard(Page page) {
        page.navigate("/?device=living");
        assertThat(page.locator(".rail[data-rail]").first()).isAttached();
        return (List<String>) page.evaluate(
                "Array.from(document.querySelectorAll('.rail[data-rail]')).map(rail => rail.dataset.rail)");
    }

    @BrowserTest
    void pinnedLinksAreAddedRenamedReorderedAndRemoved(String browser) {
        try (BrowserSession session = open(browser)) {
            Page page = session.page();
            page.navigate("/setup#pinned");
            Locator pinned = page.locator("#pinned");

            pinned.getByLabel("Link", new Locator.GetByLabelOptions().setExact(true))
                    .fill("https://www.netflix.com/title/80057281");
            pinned.getByLabel("Title (optional)").fill("Stranger Things");
            button(pinned, "Pin link").click();
            assertThat(pinned).containsText("Pinned Stranger Things");
            pinned.getByLabel("Link", new Locator.GetByLabelOptions().setExact(true))
                    .fill("https://www.youtube.com/watch?v=aqz-KE-bpKQ");
            pinned.getByLabel("Title (optional)").fill("Bunny");
            button(pinned, "Pin link").click();
            assertThat(pinned).containsText("Pinned Bunny");

            button(pinned, "Move Bunny up").click();
            assertThat(pinned).containsText("Order saved");
            Locator first = pinned.locator(".pinned-list > li").first();
            assertThat(first).containsText("Bunny");
            Locator strangerThings = pinned.locator(".pinned-list > li").filter(
                    new Locator.FilterOptions().setHasText("Stranger Things"));
            strangerThings.getByLabel("Link title").fill("Stranger Things S1");
            button(strangerThings, "Rename").click();
            assertThat(pinned).containsText("Renamed to Stranger Things S1");

            page.navigate("/?device=living");
            Locator tiles = page.locator(".rail[data-rail='pinned/pinned'] .tile");
            assertThat(tiles).hasCount(2);
            assertThat(tiles.nth(0)).hasAttribute("data-title", "Bunny");
            assertThat(tiles.nth(1)).hasAttribute("data-title", "Stranger Things S1");

            page.navigate("/setup#pinned");
            button(pinned.locator(".pinned-list > li").first(), "Remove").click();
            assertThat(pinned).containsText("Removed Bunny");
            button(pinned.locator(".pinned-list > li").first(), "Remove").click();
            assertThat(pinned).containsText("No pinned links yet.");
        }
    }

    @BrowserTest
    void aHiddenOrMovedRailChangesTheDashboard(String browser) {
        try (BrowserSession session = open(browser)) {
            Page page = session.page();
            List<String> before = railsOnTheDashboard(page);
            org.assertj.core.api.Assertions.assertThat(before).startsWith("e2e/picks", "e2e/flaky");

            page.navigate("/setup#sources");
            Locator picks = page.locator("ol.rail-preferences > li").filter(
                    new Locator.FilterOptions().setHasText("Picks · E2E"));
            assertThat(button(picks, "Move up")).isDisabled();
            button(picks, "Move down").click();
            assertThat(page.locator("#sources")).containsText("Rail order saved");
            org.assertj.core.api.Assertions.assertThat(railsOnTheDashboard(page)).startsWith("e2e/flaky", "e2e/picks");

            page.navigate("/setup#sources");
            button(picks, "Hide").click();
            assertThat(page.locator("#sources")).containsText("Picks is hidden");
            org.assertj.core.api.Assertions.assertThat(railsOnTheDashboard(page)).doesNotContain("e2e/picks");

            page.navigate("/setup#sources");
            button(picks, "Show").click();
            assertThat(page.locator("#sources")).containsText("Picks is shown");
            org.assertj.core.api.Assertions.assertThat(railsOnTheDashboard(page)).contains("e2e/picks");
        }
    }

    @BrowserTest
    void aSourceHiddenFromTheDashboardTakesItsRailsAndSearchAlong(String browser) {
        try (BrowserSession session = open(browser)) {
            Page page = session.page();
            page.navigate("/setup#sources");
            Locator source = page.locator("ul.source-preferences > li").filter(
                    new Locator.FilterOptions().setHasText("E2E"));

            button(source, "Hide from dashboard").click();

            assertThat(page.locator("#sources")).containsText("E2E is hidden from the dashboard and search");
            page.navigate("/?device=living");
            assertThat(page.locator(".rail[data-rail^='e2e/']")).hasCount(0);
            page.locator("#search-q").fill("bunny");
            assertThat(page.locator("#search-results")).not().isEmpty();
            assertThat(page.locator("#search-results .tile")).hasCount(0);

            page.navigate("/setup#sources");
            button(source, "Show on dashboard").click();
            assertThat(page.locator("#sources")).containsText("E2E is shown on the dashboard");
            page.navigate("/?device=living");
            assertThat(page.locator(".rail[data-rail='e2e/picks']")).isVisible();
        }
    }

    @BrowserTest
    void aForgottenDeviceLeavesTheDashboard(String browser) {
        try (BrowserSession session = open(browser)) {
            Page page = session.page();
            page.navigate("/setup#paired-devices");
            Locator speaker = page.locator("#paired-devices li").filter(
                    new Locator.FilterOptions().setHasText("Speaker at 127.0.0.1"));

            button(speaker, "Forget").click();

            assertThat(page.locator("#paired-devices")).not().containsText("Speaker at 127.0.0.1");
            page.navigate("/?device=living");
            assertThat(page.locator("a.chip[data-device='speaker']")).hasCount(0);
            assertThat(page.locator("a.chip[data-device='living']")).isVisible();
        }
    }

    @BrowserTest
    void aPasswordSetInSetupGuardsTheAppUntilItIsRemoved(String browser) {
        try (BrowserSession session = open(browser)) {
            Page page = session.page();
            page.navigate("/setup#account");
            Locator account = page.locator("#account");

            account.getByLabel("New password", new Locator.GetByLabelOptions().setExact(true)).fill(PASSWORD);
            account.getByLabel("Confirm new password", new Locator.GetByLabelOptions().setExact(true)).fill(PASSWORD);
            button(account, "Set password").click();
            assertThat(account).containsText("Password set. Every browser now needs it to open Home Control.");

            button(account, "Log out").click();
            assertThat(page).hasURL(Pattern.compile(".*/login.*"));
            page.navigate("/?device=living");
            assertThat(page).hasURL(Pattern.compile(".*/login.*"));

            page.locator("input[name=password]").fill(PASSWORD);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Log in")).click();
            assertThat(page.locator(".rail[data-rail='e2e/picks']")).isVisible();

            page.navigate("/setup#account");
            account.locator("form[action='/setup/password/remove']").getByLabel("Current password").fill(PASSWORD);
            button(account, "Remove password").click();
            assertThat(account).containsText("Password removed. Anyone on your network can open Home Control.");

            try (BrowserSession stranger = Browsers.open(browser, baseUrl(), traceName + "-stranger")) {
                stranger.page().navigate("/?device=living");
                assertThat(stranger.page().locator(".rail[data-rail='e2e/picks']")).isVisible();
            }
        }
    }
}

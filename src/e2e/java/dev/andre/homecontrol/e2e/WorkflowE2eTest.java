package dev.andre.homecontrol.e2e;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.BoundingBox;
import com.microsoft.playwright.options.ScreenshotAnimations;
import dev.andre.homecontrol.core.Action;
import dev.andre.homecontrol.security.LoginService;
import dev.andre.homecontrol.sources.workflows.FakeWorkflowServer;
import dev.andre.homecontrol.sources.workflows.WorkflowStore;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Workflow editing and Cast selection in the actual browser, against loopback JSON and recorded actions. */
class WorkflowE2eTest extends E2eApplicationTest {
    private static final String PASSWORD = "workflow browser password";
    private static final String TOKEN = "browser-secret-token-one";
    private static final String NEXT_TOKEN = "browser-secret-token-two";
    private static final String SOURCE_SECRET = "browser-source-secret";
    private static final String MEDIA_SECRET = "browser-media-secret";
    private static final String HEADER_SECRET = "browser-header-secret";

    @DynamicPropertySource
    static void workflowLoopback(DynamicPropertyRegistry registry) {
        registry.add("home-control.workflows.allow-loopback", () -> "true");
    }

    @Autowired WorkflowStore workflows;
    @Autowired LoginService login;

    @AfterEach void clearWorkflows() {
        if (workflows.all().isEmpty()) return;
        var request = new MockHttpServletRequest();
        org.assertj.core.api.Assertions.assertThat(login.authenticate(PASSWORD, request)).isTrue();
        for (var definition : workflows.all()) workflows.remove(definition.id(), definition.revision(), request);
    }

    private static FakeWorkflowServer upstream() {
        try { return new FakeWorkflowServer(); }
        catch (IOException e) { throw new IllegalStateException(e); }
    }

    private static void feed(FakeWorkflowServer upstream, String token, boolean generated) {
        String body = generated
                ? "{\"auth\":{\"token\":\"" + token + "\"},\"channels\":[{\"id\":\"news\",\"title\":\"News\",\"quality\":\"hd\"},{\"id\":\"music\",\"title\":\"Music\",\"quality\":\"sd\"}]}"
                : "{\"id\":\"news\",\"auth\":{\"token\":\"" + token + "\"}}";
        upstream.respond("/feed", 200, body);
    }

    private static Locator button(Page page, String name) {
        return page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName(name));
    }

    private static Locator label(Page page, String name) {
        return page.getByLabel(name, new Page.GetByLabelOptions().setExact(true));
    }

    private static Locator mapping(Page page, int index) {
        return page.locator("[data-rows='variables'] [data-row='variables']").nth(index);
    }

    private static void addMapping(Page page, int index, String name, String scope, String pointer, boolean sensitive) {
        button(page, "Add mapping").click();
        Locator row = mapping(page, index);
        row.getByLabel("Variable name").fill(name);
        row.getByLabel("Read from").selectOption(scope);
        row.getByLabel("JSON Pointer").fill(pointer);
        assertThat(row.getByLabel("Sensitive value")).isChecked();
        if (!sensitive) row.getByLabel("Sensitive value").uncheck();
    }

    private static void fillNew(Page page, FakeWorkflowServer upstream, boolean generated) {
        page.navigate("/setup/workflows/new");
        label(page, "Workflow name").fill(generated ? "Generated News" : "Single News");
        label(page, "Tile mode").selectOption(generated ? "GENERATED" : "SINGLE");
        label(page, "New source URL").fill(upstream.url("/feed?key=" + SOURCE_SECRET).toString());
        button(page, "Add header").click();
        page.locator("[data-rows='headers'] [data-row='headers']").getByLabel("Header name").fill("Authorization");
        page.locator("[data-rows='headers'] [data-row='headers']").getByLabel("New header value").fill(HEADER_SECRET);
        label(page, "New media URL template").fill(upstream.url("/media/" + MEDIA_SECRET) + "?id={A}&token={C}");
        label(page, "Direct media MIME type").fill("video/mp4");
        assertThat(page.locator("#workflow-action")).containsText("selected on the Dashboard");
        assertThat(page.locator("#workflow-mime-presets option")).hasCount(4);
        if (generated) {
            label(page, "Entry array pointer").fill("/channels");
            label(page, "Entry ID pointer").fill("/id");
            label(page, "Entry title pointer").fill("/title");
        } else label(page, "Tile title").fill("News");
        addMapping(page, 0, "A", generated ? "ENTRY" : "ROOT", "/id", false);
        addMapping(page, 1, "C", "ROOT", "/auth/token", true);
        label(page, "Home Control password (10–1024 characters)").fill(PASSWORD);
        label(page, "Confirm Home Control password").fill(PASSWORD);
        button(page, "Save workflow").click();
        assertThat(page).hasURL(Pattern.compile(".*/setup/workflows/w-[0-9a-f]{12}$"));
        org.assertj.core.api.Assertions.assertThat(page.context().cookies()).isNotEmpty();
        assertThat(label(page, "Workflow name")).hasValue(generated ? "Generated News" : "Single News");
    }

    private static void assertPrivate(Page page) {
        org.assertj.core.api.Assertions.assertThat(page.content())
                .doesNotContain(TOKEN, NEXT_TOKEN, SOURCE_SECRET, MEDIA_SECRET, HEADER_SECRET);
    }

    private static void assertFits(Page page, int width) {
        Number overflow = (Number) page.evaluate("""
                () => Math.max(document.documentElement.scrollWidth, document.body.scrollWidth)
                    - document.documentElement.clientWidth
                """);
        org.assertj.core.api.Assertions.assertThat(overflow.doubleValue()).as("Overflow at %s px", width).isLessThanOrEqualTo(1);
        for (Locator control : page.locator("input:visible, select:visible, button:visible").all()) {
            BoundingBox box = control.boundingBox();
            if (box == null) continue;
            org.assertj.core.api.Assertions.assertThat(box.x).isGreaterThanOrEqualTo(-1);
            org.assertj.core.api.Assertions.assertThat(box.x + box.width).isLessThanOrEqualTo(width + 1);
        }
    }

    private static void screenshot(Page page, String browser, int width, String view) {
        Path path = Path.of("/tmp/workflow-" + view + "-" + width + "-" + browser + ".png");
        page.screenshot(new Page.ScreenshotOptions().setPath(path).setFullPage(true)
                .setAnimations(ScreenshotAnimations.DISABLED));
    }

    @BrowserTest
    void mappingScopesFollowModeIncludingNewRowsAndTransitions(String browser) {
        try (BrowserSession session = open(browser)) {
            Page page = session.page();
            page.navigate("/setup/workflows/new");
            button(page, "Add mapping").click();
            assertThat(mapping(page, 0).getByLabel("Sensitive value")).isChecked();
            assertThat(mapping(page, 0).locator("select[data-field='scope'] option")).hasCount(1);
            assertThat(mapping(page, 0).getByLabel("Read from")).hasValue("ROOT");
            label(page, "Tile mode").selectOption("GENERATED");
            assertThat(mapping(page, 0).locator("select[data-field='scope'] option")).hasCount(2);
            mapping(page, 0).getByLabel("Read from").selectOption("ENTRY");
            label(page, "Tile mode").selectOption("SINGLE");
            assertThat(mapping(page, 0).getByLabel("Read from")).hasValue("ROOT");
            assertThat(mapping(page, 0).locator("select[data-field='scope'] option")).hasCount(1);
            assertThat(page.locator("#workflow-mappings")).containsText("Switching to one tile changes all mappings to Whole response");
            button(page, "Add mapping").click();
            assertThat(mapping(page, 1).locator("select[data-field='scope'] option")).hasCount(1);
            label(page, "Tile mode").selectOption("GENERATED");
            assertThat(mapping(page, 0).getByLabel("Read from")).hasValue("ROOT");
            assertThat(mapping(page, 1).locator("select[data-field='scope'] option")).hasCount(2);
            mapping(page, 1).getByLabel("Read from").selectOption("ENTRY");
            assertThat(mapping(page, 1).getByLabel("Read from")).hasValue("ENTRY");
        }
    }

    @BrowserTest
    void singleSaveKeepTestTileAndExplicitRetry(String browser) {
        try (FakeWorkflowServer upstream = upstream(); BrowserSession session = open(browser)) {
            feed(upstream, TOKEN, false);
            Page page = session.page();
            page.navigate("/setup");
            Locator workflowsLink = page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Workflows"));
            assertThat(workflowsLink).isVisible();
            workflowsLink.click();
            assertThat(page).hasURL(Pattern.compile(".*/setup#workflows$"));
            page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Create workflow")).click();
            fillNew(page, upstream, false);
            org.assertj.core.api.Assertions.assertThat(upstream.count("/feed")).isZero();
            assertPrivate(page);
            label(page, "Workflow name").fill("");
            button(page, "Save workflow").click();
            assertThat(page.locator(".error a[href='#workflow-name']")).isVisible();
            assertThat(label(page, "Source URL")).hasValue("KEEP");
            assertThat(label(page, "Request headers")).hasValue("KEEP");
            assertThat(label(page, "Media URL template")).hasValue("KEEP");
            assertThat(label(page, "New source URL")).hasValue("");
            assertPrivate(page);
            label(page, "Workflow name").fill("Single News");
            button(page, "Save workflow").click();
            assertThat(page).hasURL(Pattern.compile(".*/setup/workflows/w-[0-9a-f]{12}$"));

            for (int width : new int[]{320, 390, 1440}) {
                page.setViewportSize(width, width == 1440 ? 1000 : 844);
                assertFits(page, width);
                if (width != 320) screenshot(page, browser, width, "editor");
            }
            assertThat(mapping(page, 0).getByLabel("Sensitive value")).not().isChecked();
            assertThat(mapping(page, 1).getByLabel("Sensitive value")).isChecked();
            page.navigate("/setup#workflows");
            Locator setup = page.locator("#workflows");
            assertThat(setup).containsText("Single tile");
            assertThat(setup.getByRole(AriaRole.LINK, new Locator.GetByRoleOptions().setName("Edit").setExact(true))).isVisible();
            assertThat(setup).containsText("Fetches fresh data");
            assertThat(setup).containsText("without playback");
            Locator testForm = setup.locator("form[action$='/test']");
            assertThat(testForm).hasAttribute("method", "post");
            assertThat(testForm.locator("input[name='expectedRevision']")).hasValue(Long.toString(workflows.all().getFirst().revision()));
            org.assertj.core.api.Assertions.assertThat(upstream.count("/feed")).isZero();
            for (int width : new int[]{390, 1440}) {
                page.setViewportSize(width, width == 1440 ? 1000 : 844);
                assertFits(page, width);
                screenshot(page, browser, width, "setup-final");
            }
            testForm.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Test workflow")).click();
            assertThat(page.locator(".workflow-stages")).containsText("Fetch JSON");
            assertThat(page.locator(".workflow-sample")).containsText("News");
            assertThat(page.locator(".workflow-sample")).containsText("C = •••");
            assertPrivate(page);
            org.assertj.core.api.Assertions.assertThat(upstream.count("/feed")).isEqualTo(1);
            org.assertj.core.api.Assertions.assertThat(fakeDevices.recorded()).isEmpty();

            page.navigate("/?device=bedroom");
            Locator tile = page.locator("button.tile[data-source='workflows']");
            assertThat(tile).isVisible();
            List<String> flakyIds = page.locator(".rail[data-rail^='e2e/flaky']").all().stream()
                    .map(rail -> rail.getAttribute("data-rail")).toList();
            org.assertj.core.api.Assertions.assertThat(flakyIds).hasSize(5).doesNotHaveDuplicates();
            for (int width : new int[]{320, 390, 1440}) {
                page.setViewportSize(width, width == 1440 ? 1000 : 844);
                assertFits(page, width);
                if (width != 320) screenshot(page, browser, width, "dashboard");
            }
            int beforeSheet = upstream.count("/feed");
            tile.click();
            assertThat(page.locator("#sheet-route")).containsText("Cast with the Default Media Receiver");
            org.assertj.core.api.Assertions.assertThat(upstream.count("/feed")).isEqualTo(beforeSheet);
            org.assertj.core.api.Assertions.assertThat(fakeDevices.recorded("bedroom")).isEmpty();
            page.locator("[data-sheet-device='speaker']").click();
            assertThat(page.locator("#sheet-play")).isDisabled();
            page.locator("[data-sheet-device='bedroom']").click();
            assertThat(page.locator("#sheet-play")).isEnabled();
            page.locator("#sheet-play").click();
            await().untilAsserted(() -> org.assertj.core.api.Assertions.assertThat(fakeDevices.recorded("bedroom"))
                    .filteredOn(Action.CastLoad.class::isInstance).hasSize(1));
            org.assertj.core.api.Assertions.assertThat(upstream.count("/feed")).isEqualTo(beforeSheet + 1);

            upstream.respond("/feed", 503, "{\"secret\":\"" + TOKEN + "\"}");
            tile.click(); page.locator("#sheet-play").click();
            assertThat(page.locator("#toast")).containsText("failed");
            org.assertj.core.api.Assertions.assertThat(fakeDevices.recorded("bedroom")).filteredOn(Action.CastLoad.class::isInstance).hasSize(1);
            feed(upstream, NEXT_TOKEN, false);
            tile.click(); page.locator("#sheet-play").click();
            await().untilAsserted(() -> org.assertj.core.api.Assertions.assertThat(fakeDevices.recorded("bedroom"))
                    .filteredOn(Action.CastLoad.class::isInstance).hasSize(2));
            List<Action> actions = fakeDevices.recorded("bedroom");
            org.assertj.core.api.Assertions.assertThat(((Action.CastLoad) actions.getLast()).load().toString())
                    .contains("token=" + NEXT_TOKEN);
            org.assertj.core.api.Assertions.assertThat(page.content()).doesNotContain(TOKEN, NEXT_TOKEN);
        }
    }

    @BrowserTest
    void generatedEditReindexErrorLinksAndStablePlay(String browser) {
        try (FakeWorkflowServer upstream = upstream(); BrowserSession session = open(browser)) {
            feed(upstream, TOKEN, true);
            Page page = session.page();
            fillNew(page, upstream, true);
            String id = workflows.all().getFirst().id();
            assertThat(mapping(page, 0).getByLabel("Variable name")).hasValue("A");
            assertThat(mapping(page, 1).getByLabel("Variable name")).hasValue("C");
            mapping(page, 1).getByLabel("Variable name").fill("1invalid");
            button(page, "Save workflow").click();
            Locator error = page.locator(".error a[href='#workflow-variables-1-name']");
            assertThat(error).isVisible();
            button(page, "Add mapping").click();
            assertThat(error).isVisible();
            assertThat(mapping(page, 2).getByLabel("Variable name")).hasAttribute("name", "variables[2].name");
            mapping(page, 0).getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Remove mapping")).click();
            Locator moved = page.locator(".error a[href='#workflow-variables-0-name']");
            assertThat(moved).isVisible();
            moved.click();
            assertThat(mapping(page, 0).getByLabel("Variable name")).isFocused();
            assertThat(mapping(page, 0).getByLabel("Variable name")).hasAttribute("name", "variables[0].name");
            mapping(page, 0).getByLabel("Variable name").fill("C");
            mapping(page, 1).getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Remove mapping")).click();
            addMapping(page, 1, "A", "ENTRY", "/id", false);
            button(page, "Save workflow").click();
            assertThat(page).hasURL(Pattern.compile(".*/setup/workflows/" + id + "$"));
            assertPrivate(page);
            button(page, "Test workflow").click();
            assertThat(page.locator(".workflow-sample")).hasCount(2);
            assertPrivate(page);
            org.assertj.core.api.Assertions.assertThat(fakeDevices.recorded()).isEmpty();

            page.navigate("/?device=bedroom");
            Locator news = page.locator("button.tile[data-source='workflows']").filter(new Locator.FilterOptions().setHasText("News"));
            assertThat(news).isVisible();
            int beforeSheet = upstream.count("/feed");
            news.click();
            assertThat(page.locator("#sheet-route")).containsText("Cast with the Default Media Receiver");
            org.assertj.core.api.Assertions.assertThat(upstream.count("/feed")).isEqualTo(beforeSheet);
            upstream.respond("/feed", 200, "{\"auth\":{\"token\":\"" + NEXT_TOKEN + "\"},\"channels\":[{\"id\":\"music\",\"title\":\"Music\"},{\"id\":\"news\",\"title\":\"News\"}]}");
            page.locator("#sheet-play").click();
            await().untilAsserted(() -> org.assertj.core.api.Assertions.assertThat(fakeDevices.recorded("bedroom"))
                    .filteredOn(Action.CastLoad.class::isInstance).hasSize(1));
            org.assertj.core.api.Assertions.assertThat(((Action.CastLoad) fakeDevices.recorded("bedroom").getFirst()).load().toString())
                    .contains("id=news", "token=" + NEXT_TOKEN);
        }
    }
}

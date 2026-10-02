package dev.andre.homecontrol.e2e;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.Route;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.ScreenshotAnimations;
import dev.andre.homecontrol.testsupport.FakeHttpServer;
import dev.andre.homecontrol.themes.ThemeCatalog;
import dev.andre.homecontrol.themes.ThemeDescriptor;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.regex.Pattern;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class OfflineUiE2eTest extends E2eApplicationTest {

    @Autowired
    private ThemeCatalog themes;

    @AfterEach
    void removeImportedThemes() {
        themes.themes().stream().filter(theme -> !theme.builtIn()).forEach(theme -> themes.remove(theme.id()));
    }

    @BrowserTest
    void offlineNavigationsKeepTheirStylesWithoutCachingLiveRequests(String browser) throws IOException, InterruptedException {
        try (OfflineOrigin origin = new OfflineOrigin(baseUrl(), themes);
             BrowserSession session = Browsers.open(browser, origin.baseUrl(),
                     "OfflineUiE2eTest-offlineNavigationsKeepTheirStylesWithoutCachingLiveRequests")) {
            // Playwright routing disables the HTTP cache. CSS must come from the worker's
            // Cache Storage after going offline, rather than a previously loaded stylesheet.
            session.context().route("**/*", Route::resume);
            Page page = session.page();
            page.navigate("/offline.html");
            assumeTrue((Boolean) page.evaluate("() => 'serviceWorker' in navigator"),
                    "This browser does not expose service workers");
            String onlineBackground = (String) page.locator("body")
                    .evaluate("body => getComputedStyle(body).backgroundColor");
            org.assertj.core.api.Assertions.assertThat(onlineBackground)
                    .as("The online stylesheet applies a page background")
                    .isNotIn("rgba(0, 0, 0, 0)", "transparent");

            // Localhost is a secure context; production registration deliberately requires HTTPS.
            page.evaluate("""
                    async () => {
                        await caches.open('unrelated-application-cache');
                        await navigator.serviceWorker.register('/sw.js');
                        await navigator.serviceWorker.ready;
                    }
                    """);
            page.waitForFunction("() => Boolean(navigator.serviceWorker.controller)");
            org.assertj.core.api.Assertions.assertThat(page.evaluate("() => caches.has('unrelated-application-cache')"))
                    .as("Activation leaves other applications' caches alone").isEqualTo(true);
            // Stop the real origin: WebKit's offline emulation rejects navigations before
            // their service worker can respond. An unavailable server exercises both engines.
            origin.disconnect();

            for (int width : new int[] {390, 1440}) {
                page.setViewportSize(width, width == 390 ? 844 : 1000);
                Response response = page.navigate("/missing-page-for-offline-test?width=" + width);
                org.assertj.core.api.Assertions.assertThat(response).isNotNull();
                org.assertj.core.api.Assertions.assertThat(response.fromServiceWorker())
                        .as("The worker answers navigation while the origin is unavailable").isTrue();
                assertThat(page).hasTitle("Home Control is offline");
                Locator retry = page.getByRole(AriaRole.LINK,
                        new Page.GetByRoleOptions().setName(Pattern.compile("Try again")));
                assertThat(retry).isVisible();
                screenshot(page, browser, width, "");
                assertThat(page.locator("body")).hasCSS("background-color", onlineBackground);
                assertThat(retry).hasCSS("display", "inline-flex");
            }

            // The theme switch is cached too, so the offline page keeps a chosen theme.
            page.evaluate("() => localStorage.setItem('homecontrol.theme.v1', 'cyberpunk')");
            page.setViewportSize(390, 844);
            page.navigate("/missing-page-for-offline-test?theme=cyberpunk");
            assertThat(page.locator("html")).hasAttribute("data-theme", "cyberpunk");
            assertThat(page.locator("body")).hasCSS("background-color", "rgb(7, 8, 13)");
            screenshot(page, browser, 390, "cyberpunk-");

            org.assertj.core.api.Assertions.assertThat(page.evaluate("""
                    async () => {
                        const response = await fetch('/icons/icon.svg', { cache: 'no-store' });
                        return response.ok && (await response.text()).includes('<svg');
                    }
                    """)).as("The cached app icon remains available offline").isEqualTo(true);

            Object unexpectedlyAvailable = page.evaluate("""
                    async () => {
                        const requests = [
                            { url: '/setup' },
                            { url: '/manifest.webmanifest' },
                            { url: '/events' },
                            { url: '/js/app.js' },
                            { url: '/app.css', method: 'POST' }
                        ];
                        const results = await Promise.all(requests.map(async ({ url, method = 'GET' }) => {
                            try {
                                await fetch(url, { method, cache: 'no-store' });
                                return `${method} ${url}`;
                            } catch {
                                return null;
                            }
                        }));
                        return results.filter(Boolean);
                    }
                    """);
            org.assertj.core.api.Assertions.assertThat((List<?>) unexpectedlyAvailable)
                    .as("Live pages, JSON, event streams, scripts, and writes require the server").isEmpty();

            String font = themes.require("cyberpunk").assets().stream()
                    .filter(path -> path.endsWith(".woff2")).findFirst().orElseThrow();
            page.evaluate("""
                    async font => {
                        const name = (await caches.keys()).find(key => key.startsWith('home-control-presentation-'));
                        await (await caches.open(name)).delete(font);
                    }
                    """, font);
            page.navigate("/offline-after-built-in-font-eviction");
            assertThat(page.locator("html")).hasAttribute("data-theme", "default");
            assertThat(page.locator("body")).hasCSS("background-color", onlineBackground);
            assertThat(page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName(Pattern.compile("Try again"))))
                    .isVisible();
        }
    }

    @BrowserTest
    void offlineKeepsOneCompleteCustomRevisionAndFallsBackAfterAnAssetIsEvicted(String browser)
            throws IOException, InterruptedException {
        ThemeDescriptor ocean = themes.install(ThemePackageFixtures.derivative(themes.export("cyberpunk"),
                "ocean", "Ocean", "#123456"), null);
        ThemeDescriptor forest = themes.install(ThemePackageFixtures.derivative(themes.export("cyberpunk"),
                "forest", "Forest", "#234567"), null);
        try (OfflineOrigin origin = new OfflineOrigin(baseUrl(), themes);
             BrowserSession session = Browsers.open(browser, origin.baseUrl(), traceName)) {
            session.context().route("**/*", Route::resume);
            Page page = session.page();
            page.navigate("/offline.html");
            assumeTrue((Boolean) page.evaluate("() => 'serviceWorker' in navigator"),
                    "This browser does not expose service workers");
            page.evaluate("""
                    async () => {
                        await navigator.serviceWorker.register('/sw.js');
                        await navigator.serviceWorker.ready;
                    }
                    """);
            page.waitForFunction("() => Boolean(navigator.serviceWorker.controller)");
            page.evaluate("id => window.homeControlTheme.select(id)", ocean.id());
            waitForCompleteCustomCache(page, ocean);
            page.evaluate("id => window.homeControlTheme.select(id)", forest.id());
            waitForCompleteCustomCache(page, forest);
            origin.disconnect();

            page.navigate("/offline-custom-theme");
            assertThat(page.locator("html")).hasAttribute("data-theme", "forest");
            assertThat(page.locator("body")).hasCSS("background-color", "rgb(35, 69, 103)");
            org.assertj.core.api.Assertions.assertThat(page.evaluate("""
                    async paths => {
                        const family = getComputedStyle(document.body).fontFamily.split(',')[0].trim().replaceAll('"', '');
                        const weights = ['500', '700'];
                        await Promise.all(weights.map(weight => document.fonts.load(`${weight} 16px "${family}"`)));
                        const responses = await Promise.all(paths.map(path => fetch(path, {cache: 'no-store'})));
                        return responses.every(response => response.ok) && family.startsWith('theme-forest-')
                            && weights.every(weight => Array.from(document.fonts).some(face =>
                                face.family.replaceAll('"', '') === family && face.weight === weight && face.status === 'loaded'));
                    }
                    """, forest.assets())).as("The full custom revision, including its fonts, is available offline")
                    .isEqualTo(true);
            screenshot(page, browser, 390, "custom-");

            page.evaluate("id => localStorage.setItem('homecontrol.theme.v1', id)", ocean.id());
            page.navigate("/offline-previous-custom-theme");
            assertThat(page.locator("html")).hasAttribute("data-theme", "default");
            assertThat(page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName(Pattern.compile("Try again"))))
                    .isVisible();

            // An incomplete revision is removed from the offline catalog, even if its CSS survived eviction.
            String font = forest.assets().stream().filter(path -> path.endsWith(".woff2")).findFirst().orElseThrow();
            page.evaluate("""
                    async ({id, font}) => {
                        localStorage.setItem('homecontrol.theme.v1', id);
                        const name = (await caches.keys()).find(key => key.startsWith('home-control-theme-'));
                        await (await caches.open(name)).delete(font);
                    }
                    """, Map.of("id", forest.id(), "font", font));
            page.navigate("/offline-incomplete-custom-theme");
            assertThat(page.locator("html")).hasAttribute("data-theme", "default");
            assertThat(page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName(Pattern.compile("Try again"))))
                    .isVisible();
        }
    }

    private static void waitForCompleteCustomCache(Page page, ThemeDescriptor theme) {
        page.waitForFunction("""
                async ({id, revision, paths}) => {
                    const keys = (await caches.keys()).filter(key => key.startsWith('home-control-theme-'));
                    if (keys.length !== 1) return false;
                    const cache = await caches.open(keys[0]);
                    const response = await cache.match('/__home-control-theme/descriptor.json');
                    if (!response) return false;
                    const metadata = await response.json();
                    return metadata.theme.id === id && metadata.theme.revision === revision
                        && (await Promise.all(paths.map(path => cache.match(path)))).every(Boolean);
                }
                """, Map.of("id", theme.id(), "revision", theme.revision(), "paths", theme.assets()));
    }

    private static void screenshot(Page page, String browser, int width, String prefix) {
        Path path = Path.of(System.getProperty("e2e.artifacts", "build/e2e-artifacts"),
                "ui-" + prefix + "offline-" + width + "-" + browser + ".png");
        page.screenshot(new Page.ScreenshotOptions().setPath(path).setFullPage(true)
                .setAnimations(ScreenshotAnimations.DISABLED));
    }

    /** A stoppable origin snapshots the real server-rendered shell and exact public package resources. */
    private static final class OfflineOrigin implements AutoCloseable {
        private final FakeHttpServer server;
        private final String baseUrl;

        private OfflineOrigin(String liveOrigin, ThemeCatalog themes) throws IOException, InterruptedException {
            Map<String, Resource> resources = snapshot(liveOrigin, themes);
            server = FakeHttpServer.start();
            baseUrl = server.url().toString();
            resources.forEach((path, resource) -> server.respond("GET", path,
                    dev.andre.homecontrol.testsupport.Response.of(200, resource.type(), resource.bytes())
                            .withHeader("Cache-Control", "no-store")));
        }

        private static Map<String, Resource> snapshot(String origin, ThemeCatalog themes)
                throws IOException, InterruptedException {
            Set<String> paths = new LinkedHashSet<>(List.of("/offline.html", "/sw.js", "/app.css",
                    "/js/theme.js", "/icons/icon.svg", "/themes/catalog.js", "/themes/catalog.json"));
            for (ThemeDescriptor theme : themes.themes()) {
                paths.add(theme.stylesheet());
                paths.addAll(theme.assets());
            }
            Map<String, Resource> resources = new LinkedHashMap<>();
            try (HttpClient client = HttpClient.newHttpClient()) {
                for (String path : paths) {
                    var response = client.send(HttpRequest.newBuilder(URI.create(origin + path)).GET().build(),
                            HttpResponse.BodyHandlers.ofByteArray());
                    if (response.statusCode() != 200) throw new IOException("Cannot snapshot " + path + ": " + response.statusCode());
                    resources.put(path, new Resource(response.headers().firstValue("content-type")
                            .orElse("application/octet-stream"), response.body()));
                }
            }
            return resources;
        }

        private String baseUrl() { return baseUrl; }

        private void disconnect() { server.close(); }

        @Override
        public void close() { disconnect(); }

        private record Resource(String type, byte[] bytes) { }
    }
}

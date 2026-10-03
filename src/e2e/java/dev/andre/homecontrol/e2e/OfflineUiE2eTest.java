package dev.andre.homecontrol.e2e;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.Route;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.Cookie;
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
import java.util.concurrent.CountDownLatch;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class OfflineUiE2eTest extends E2eApplicationTest {

    private static final String REPORTING_WORKER = "/sw-reporting.js";

    @Autowired
    private ThemeCatalog themes;

    @AfterEach
    void removeImportedThemes() {
        themes.themes().stream().filter(theme -> !theme.builtIn()).forEach(theme -> themes.remove(theme.id()));
    }

    /**
     * The worker only exists behind an HTTPS proxy, and a proxy whose server is down or restarting answers 502, 503 or
     * 504 instead of refusing the connection: those get the offline page too. Other answers pass through.
     */
    @BrowserTest
    void aProxyThatCannotReachTheServerGetsTheOfflinePage(String browser) throws IOException, InterruptedException {
        try (OfflineOrigin origin = new OfflineOrigin(baseUrl(), themes);
             BrowserSession session = Browsers.open(browser, origin.baseUrl(),
                     "OfflineUiE2eTest-aProxyThatCannotReachTheServerGetsTheOfflinePage")) {
            session.context().route("**/*", Route::resume);
            Page page = session.page();
            page.navigate("/offline.html");
            assumeTrue((Boolean) page.evaluate("() => 'serviceWorker' in navigator"),
                    "This browser does not expose service workers");
            page.evaluate("""
                    async script => {
                        await navigator.serviceWorker.register(script);
                        await navigator.serviceWorker.ready;
                    }
                    """, REPORTING_WORKER);
            page.waitForFunction("() => Boolean(navigator.serviceWorker.controller)");
            for (int status : new int[] {502, 503, 504, 404}) {
                origin.answer("/answered-" + status, status);
            }

            for (int status : new int[] {502, 503, 504}) {
                page.navigate("/answered-" + status);
                assertThat(page).hasTitle("Home Control is offline");
            }
            Response missing = page.navigate("/answered-404");
            org.assertj.core.api.Assertions.assertThat(missing).isNotNull();
            org.assertj.core.api.Assertions.assertThat(missing.status()).isEqualTo(404);
            assertThat(page).hasTitle("Answered 404");
        }
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
                    async script => {
                        await caches.open('unrelated-application-cache');
                        await navigator.serviceWorker.register(script);
                        await navigator.serviceWorker.ready;
                    }
                    """, REPORTING_WORKER);
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
                org.assertj.core.api.Assertions.assertThat(response.headerValue("content-security-policy"))
                        .as("The offline page keeps the live Content-Security-Policy").isEqualTo(origin.shellPolicy());
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
                    async script => {
                        await navigator.serviceWorker.register(script);
                        await navigator.serviceWorker.ready;
                    }
                    """, REPORTING_WORKER);
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

    @BrowserTest
    void aCookieAuthenticatingProxyAllowsTheOfflineShellAndCustomTheme(String browser)
            throws IOException, InterruptedException {
        ThemeDescriptor ocean = themes.install(ThemePackageFixtures.derivative(themes.export("default"),
                "ocean", "Ocean", "#123456"), null);
        try (OfflineOrigin origin = new OfflineOrigin(baseUrl(), themes);
             BrowserSession session = Browsers.open(browser, origin.baseUrl(), traceName)) {
            origin.requireProxyCookie();
            session.context().addCookies(List.of(new Cookie("proxy-session", "authenticated").setUrl(origin.baseUrl())));
            Page page = session.page();
            page.navigate("/offline.html");
            registerWorker(page);
            page.evaluate("id => window.homeControlTheme.select(id)", ocean.id());
            waitForCompleteCustomCache(page, ocean);
            origin.disconnect();
            page.navigate("/offline-through-proxy");
            assertThat(page.locator("html")).hasAttribute("data-theme", "ocean");
            assertThat(page.locator("body")).hasCSS("background-color", "rgb(18, 52, 86)");
        }
    }

    @BrowserTest
    void aRemovedCustomThemeDoesNotReturnAfterTheServerGoesOffline(String browser)
            throws IOException, InterruptedException {
        ThemeDescriptor ocean = themes.install(ThemePackageFixtures.derivative(themes.export("default"),
                "ocean", "Ocean", "#123456"), null);
        try (OfflineOrigin origin = new OfflineOrigin(baseUrl(), themes);
             BrowserSession session = Browsers.open(browser, origin.baseUrl(), traceName)) {
            Page page = session.page();
            page.navigate("/offline.html");
            registerWorker(page);
            page.evaluate("id => window.homeControlTheme.select(id)", ocean.id());
            waitForCompleteCustomCache(page, ocean);
            themes.remove(ocean.id());
            origin.refreshResources(baseUrl(), themes);
            page.reload();
            assertThat(page.locator("html")).hasAttribute("data-theme", "default");
            org.assertj.core.api.Assertions.assertThat(page.evaluate(
                    "() => localStorage.getItem('homecontrol.theme.v1')")).isEqualTo("default");
            page.waitForCondition(() -> Boolean.TRUE.equals(page.evaluate(
                    "async () => !(await caches.keys()).some(key => key.startsWith('home-control-theme-'))")));
            origin.disconnect();
            // Even a stale tab restoring the old ID cannot advertise a revoked offline revision.
            page.evaluate("id => localStorage.setItem('homecontrol.theme.v1', id)", ocean.id());
            page.navigate("/offline-after-theme-removal");
            assertThat(page.locator("html")).hasAttribute("data-theme", "default");
            org.assertj.core.api.Assertions.assertThat(page.evaluate(
                    "() => window.homeControlThemes.themes.map(theme => theme.id)"))
                    .isEqualTo(List.of("default", "cyberpunk"));
        }
    }

    @BrowserTest
    void aTemporaryCatalogFailurePreservesTheCompleteCustomTheme(String browser)
            throws IOException, InterruptedException {
        ThemeDescriptor ocean = themes.install(ThemePackageFixtures.derivative(themes.export("default"),
                "ocean", "Ocean", "#123456"), null);
        try (OfflineOrigin origin = new OfflineOrigin(baseUrl(), themes);
             BrowserSession session = Browsers.open(browser, origin.baseUrl(), traceName)) {
            Page page = session.page();
            page.navigate("/offline.html");
            registerWorker(page);
            page.evaluate("id => window.homeControlTheme.select(id)", ocean.id());
            waitForCompleteCustomCache(page, ocean);
            origin.failCatalog();
            org.assertj.core.api.Assertions.assertThat(page.evaluate("() => window.homeControlTheme.refresh()"))
                    .isEqualTo(false);
            waitForCompleteCustomCache(page, ocean);
            origin.disconnect();
            page.navigate("/offline-after-catalog-failure");
            assertThat(page.locator("html")).hasAttribute("data-theme", "ocean");
        }
    }

    @BrowserTest
    void aSlowThemeAssetDoesNotBlockOnlineCatalogRefresh(String browser) throws IOException, InterruptedException {
        ThemeDescriptor ocean = themes.install(ThemePackageFixtures.derivative(themes.export("cyberpunk"),
                "ocean", "Ocean", "#123456"), null);
        CountDownLatch release = new CountDownLatch(1);
        try (OfflineOrigin origin = new OfflineOrigin(baseUrl(), themes);
             BrowserSession session = Browsers.open(browser, origin.baseUrl(), traceName)) {
            Page page = session.page();
            page.navigate("/offline.html");
            registerWorker(page);
            String font = ocean.assets().stream().filter(path -> path.endsWith(".woff2")).findFirst().orElseThrow();
            origin.holdAsset(font, release);
            page.evaluate("id => window.homeControlTheme.select(id)", ocean.id());
            // Wait for the worker's fetch, regardless of which font weights this browser renders on the page.
            page.waitForCondition(() -> origin.server.requests("GET", font).stream()
                    .anyMatch(request -> "empty".equals(request.header("sec-fetch-dest"))));
            org.assertj.core.api.Assertions.assertThat(page.evaluate("() => window.homeControlTheme.refresh()"))
                    .as("Online catalog refresh completes while the separate offline asset fetch is pending")
                    .isEqualTo(true);
        } finally {
            release.countDown();
        }
    }

    @BrowserTest
    void aFailedThemeUpdateKeepsItsPreviousCompleteRevisionOffline(String browser)
            throws IOException, InterruptedException {
        ThemeDescriptor ocean = themes.install(ThemePackageFixtures.derivative(themes.export("cyberpunk"),
                "ocean", "Ocean", "#123456"), null);
        try (OfflineOrigin origin = new OfflineOrigin(baseUrl(), themes);
             BrowserSession session = Browsers.open(browser, origin.baseUrl(), traceName)) {
            Page page = session.page();
            page.navigate("/offline.html");
            origin.installMessageProbe();
            registerWorker(page, "/sw-message-probe.js");
            page.evaluate("id => window.homeControlTheme.select(id)", ocean.id());
            waitForCompleteCustomCache(page, ocean);
            ThemeDescriptor updated = themes.install(ThemePackageFixtures.derivative(themes.export("ocean"),
                    "ocean", "Ocean updated", "#234567"), ocean.revision());
            origin.refreshResources(baseUrl(), themes);
            origin.failAsset(updated.assets().stream().filter(path -> path.endsWith(".woff2")).findFirst().orElseThrow());
            page.evaluate("() => window.homeControlTheme.refresh()");
            page.evaluate("""
                    ({id, revision}) => new Promise(resolve => {
                        const channel = new MessageChannel();
                        channel.port1.onmessage = () => { channel.port1.close(); resolve(); };
                        navigator.serviceWorker.controller.postMessage({type: 'test-cache-theme', id, revision},
                            [channel.port2]);
                    })
                    """, Map.of("id", updated.id(), "revision", updated.revision()));
            waitForCompleteCustomCache(page, ocean);
            origin.disconnect();
            page.navigate("/offline-after-failed-theme-update");
            assertThat(page.locator("html")).hasAttribute("data-theme", "ocean");
            assertThat(page.locator("body")).hasCSS("background-color", "rgb(18, 52, 86)");
        }
    }

    @BrowserTest
    void foreignOriginMessagesCannotReplaceTheOfflineCustomTheme(String browser)
            throws IOException, InterruptedException {
        ThemeDescriptor ocean = themes.install(ThemePackageFixtures.derivative(themes.export("default"),
                "ocean", "Ocean", "#123456"), null);
        ThemeDescriptor forest = themes.install(ThemePackageFixtures.derivative(themes.export("default"),
                "forest", "Forest", "#234567"), null);
        try (OfflineOrigin origin = new OfflineOrigin(baseUrl(), themes);
             BrowserSession session = Browsers.open(browser, origin.baseUrl(), traceName)) {
            Page page = session.page();
            page.navigate("/offline.html");
            origin.installMessageProbe();
            registerWorker(page, "/sw-message-probe.js");
            page.evaluate("id => window.homeControlTheme.select(id)", ocean.id());
            waitForCompleteCustomCache(page, ocean);
            page.evaluate("""
                    ({id, revision}) => new Promise(resolve => {
                        const channel = new MessageChannel();
                        channel.port1.onmessage = () => { channel.port1.close(); resolve(); };
                        navigator.serviceWorker.controller.postMessage({type: 'test-foreign-message', id, revision},
                            [channel.port2]);
                    })
                    """, Map.of("id", forest.id(), "revision", forest.revision()));
            waitForCompleteCustomCache(page, ocean);
            origin.disconnect();
            page.navigate("/offline-after-foreign-message");
            assertThat(page.locator("html")).hasAttribute("data-theme", "ocean");
        }
    }

    private static void registerWorker(Page page) {
        registerWorker(page, REPORTING_WORKER);
    }

    private static void registerWorker(Page page, String script) {
        assumeTrue((Boolean) page.evaluate("() => 'serviceWorker' in navigator"),
                "This browser does not expose service workers");
        page.evaluate("script => navigator.serviceWorker.register(script)", script);
        page.waitForFunction("() => Boolean(navigator.serviceWorker.controller)");
    }

    private static void waitForCompleteCustomCache(Page page, ThemeDescriptor theme) {
        page.waitForCondition(() -> Boolean.TRUE.equals(page.evaluate("""
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
                """, Map.of("id", theme.id(), "revision", theme.revision(), "paths", theme.assets()))));
    }

    private static void screenshot(Page page, String browser, int width, String prefix) {
        Path path = Path.of(System.getProperty("e2e.artifacts", "build/e2e-artifacts"),
                "ui-" + prefix + "offline-" + width + "-" + browser + ".png");
        page.screenshot(new Page.ScreenshotOptions().setPath(path).setFullPage(true)
                .setAnimations(ScreenshotAnimations.DISABLED));
    }

    /**
     * A stoppable origin snapshots the real server-rendered shell and exact public package resources, and serves each
     * under the Content-Security-Policy the live server sent with it. The tests register {@link #REPORTING_WORKER},
     * which imports the unchanged {@code /sw.js} (browser coverage compares it with the file) and reports what the
     * policy blocks inside the worker to the session.
     */
    private static final class OfflineOrigin implements AutoCloseable {
        private final FakeHttpServer server;
        private final String baseUrl;
        private final String workerPolicy;
        private final String shellPolicy;

        private OfflineOrigin(String liveOrigin, ThemeCatalog themes) throws IOException, InterruptedException {
            Map<String, Resource> resources = snapshot(liveOrigin, themes);
            server = FakeHttpServer.start();
            baseUrl = server.url().toString();
            workerPolicy = resources.get("/sw.js").policy();
            shellPolicy = resources.get("/offline.html").policy();
            resources.forEach(this::serve);
            server.respond("GET", REPORTING_WORKER, dev.andre.homecontrol.testsupport.Response.of(200,
                    "text/javascript", Browsers.reportingCspViolations("importScripts('/sw.js');\n"))
                    .withHeader("Content-Security-Policy", workerPolicy));
        }

        private void serve(String path, Resource resource) {
            server.respond("GET", path,
                    dev.andre.homecontrol.testsupport.Response.of(200, resource.type(), resource.bytes())
                            .withHeader("Cache-Control", "no-store")
                            .withHeader("Content-Security-Policy", resource.policy()));
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
                    String policy = response.headers().firstValue("content-security-policy")
                            .orElseThrow(() -> new IOException(path + " came without a Content-Security-Policy"));
                    resources.put(path, new Resource(response.headers().firstValue("content-type")
                            .orElse("application/octet-stream"), response.body(), policy));
                }
            }
            return resources;
        }

        private void installMessageProbe() {
            server.respond("GET", "/sw-message-probe.js", dev.andre.homecontrol.testsupport.Response.of(200,
                    "text/javascript", Browsers.reportingCspViolations("""
                    importScripts('/sw.js');
                    self.addEventListener('message', event => {
                        if (!['test-foreign-message', 'test-cache-theme'].includes(event.data?.type)) return;
                        event.waitUntil((async () => {
                            const work = [];
                            const message = new ExtendableMessageEvent('message', {
                                origin: event.data.type === 'test-foreign-message' ? 'https://unrelated.example' : self.location.origin,
                                data: {...event.data, type: 'homecontrol:cache-theme'}
                            });
                            Object.defineProperty(message, 'waitUntil', {value: promise => work.push(promise)});
                            self.dispatchEvent(message);
                            await Promise.all(work);
                            event.ports[0].postMessage('complete');
                        })());
                    });
                    """)).withHeader("Content-Security-Policy", workerPolicy));
        }

        private void requireProxyCookie() {
            server.respond("GET", "/**", request -> !"proxy-session=authenticated".equals(request.header("cookie")),
                    dev.andre.homecontrol.testsupport.Response.empty(401));
        }

        private void refreshResources(String liveOrigin, ThemeCatalog themes) throws IOException, InterruptedException {
            snapshot(liveOrigin, themes).forEach(this::serve);
        }

        private void holdAsset(String path, CountDownLatch release) {
            server.hold("GET", path, release, dev.andre.homecontrol.testsupport.Response.empty(503));
        }

        private void failAsset(String path) {
            server.respond("GET", path, dev.andre.homecontrol.testsupport.Response.empty(503));
        }

        private void failCatalog() {
            server.respond("GET", "/themes/catalog.json", dev.andre.homecontrol.testsupport.Response.empty(503));
        }

        private String baseUrl() { return baseUrl; }

        private String shellPolicy() { return shellPolicy; }

        private void disconnect() { server.close(); }

        /** A page a proxy would answer with this status, titled after it. */
        private void answer(String path, int status) {
            server.respond("GET", path, dev.andre.homecontrol.testsupport.Response.of(status, "text/html",
                    "<!doctype html><title>Answered " + status + "</title>"));
        }

        @Override
        public void close() { disconnect(); }

        private record Resource(String type, byte[] bytes, String policy) { }
    }
}

package dev.andre.homecontrol.e2e;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.Route;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.ScreenshotAnimations;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class OfflineUiE2eTest {

    @BrowserTest
    void offlineNavigationsKeepTheirStylesWithoutCachingLiveRequests(String browser) throws IOException {
        try (OfflineOrigin origin = new OfflineOrigin();
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
                        await navigator.serviceWorker.register('/sw.js');
                        await navigator.serviceWorker.ready;
                    }
                    """);
            page.waitForFunction("() => Boolean(navigator.serviceWorker.controller)");
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
                screenshot(page, browser, width);
                assertThat(page.locator("body")).hasCSS("background-color", onlineBackground);
                assertThat(retry).hasCSS("display", "inline-flex");
            }

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
        }
    }

    private static void screenshot(Page page, String browser, int width) {
        Path path = Path.of(System.getProperty("e2e.artifacts", "build/e2e-artifacts"),
                "ui-offline-" + width + "-" + browser + ".png");
        page.screenshot(new Page.ScreenshotOptions().setPath(path).setFullPage(true)
                .setAnimations(ScreenshotAnimations.DISABLED));
    }

    /** A stoppable origin serving the production offline resources without an HTTP cache. */
    private static final class OfflineOrigin implements AutoCloseable {
        private static final Map<String, String> TYPES = Map.of(
                "/offline.html", "text/html",
                "/sw.js", "text/javascript",
                "/app.css", "text/css",
                "/icons/icon.svg", "image/svg+xml");

        private final HttpServer server;
        private final String baseUrl;
        private boolean running = true;

        private OfflineOrigin() throws IOException {
            server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            baseUrl = "http://localhost:" + server.getAddress().getPort();
            server.createContext("/", exchange -> {
                try (exchange) {
                    String path = exchange.getRequestURI().getPath();
                    String type = TYPES.get(path);
                    if (!exchange.getRequestMethod().equals("GET") || type == null) {
                        exchange.sendResponseHeaders(404, -1);
                        return;
                    }
                    try (InputStream resource = OfflineUiE2eTest.class.getResourceAsStream("/static" + path)) {
                        if (resource == null) {
                            exchange.sendResponseHeaders(404, -1);
                            return;
                        }
                        byte[] bytes = resource.readAllBytes();
                        exchange.getResponseHeaders().set("Content-Type", type);
                        exchange.getResponseHeaders().set("Cache-Control", "no-store");
                        exchange.sendResponseHeaders(200, bytes.length);
                        exchange.getResponseBody().write(bytes);
                    }
                }
            });
            server.start();
        }

        private String baseUrl() {
            return baseUrl;
        }

        private void disconnect() {
            if (running) {
                server.stop(0);
                running = false;
            }
        }

        @Override
        public void close() {
            disconnect();
        }
    }
}

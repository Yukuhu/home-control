package dev.andre.homecontrol.e2e;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Tracing;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

/** One Playwright and one browser per kind for the whole JVM; a fresh context per test. */
public final class Browsers {

    private static final String SERVICE_WORKER_CSP_MESSAGE = "home-control-test:csp-violation";
    private static Playwright playwright;
    private static final Map<String, Browser> browsers = new HashMap<>();

    private Browsers() {
    }

    /**
     * A service worker's Content-Security-Policy blocks happen where no page listens, and no browser reports them to
     * Playwright; so a test origin that serves a worker script puts this in front of it, and the worker passes each
     * block to its pages, whose session collects it.
     */
    public static String reportingCspViolations(String workerScript) {
        return """
                self.addEventListener("securitypolicyviolation", (e) => {
                    const text = e.violatedDirective + " " + (e.blockedURI || "inline") + " in the service worker";
                    self.clients.matchAll({ includeUncontrolled: true, type: "window" }).then((pages) =>
                            pages.forEach((page) => page.postMessage({ type: "%s", text })));
                });
                """.formatted(SERVICE_WORKER_CSP_MESSAGE) + workerScript;
    }

    public static Stream<String> names() {
        return Arrays.stream(System.getProperty("e2e.browsers", "chromium,firefox,webkit").split(","))
                .map(String::strip).filter(name -> !name.isEmpty());
    }

    private static synchronized Browser browser(String name) {
        if (playwright == null) {
            playwright = Playwright.create();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> playwright.close()));
        }
        return browsers.computeIfAbsent(name, n -> {
            BrowserType type = switch (n) {
                case "chromium" -> playwright.chromium();
                case "webkit" -> playwright.webkit();
                case "firefox" -> playwright.firefox();
                default -> throw new IllegalArgumentException("Unknown browser " + n);
            };
            return type.launch(new BrowserType.LaunchOptions().setHeadless(true));
        });
    }

    public static BrowserSession open(String browser, String baseUrl, String traceName) {
        return open(browser, baseUrl, traceName, false);
    }

    public static BrowserSession open(String browser, String baseUrl, String traceName, boolean mobile) {
        return openWithPointer(browser, baseUrl, traceName, mobile, true);
    }

    /** A desktop browser with a fine pointer, distinct from the touch-enabled default contexts. */
    public static BrowserSession openDesktop(String browser, String baseUrl, String traceName) {
        return openWithPointer(browser, baseUrl, traceName, false, false);
    }

    private static BrowserSession openWithPointer(String browser, String baseUrl, String traceName,
                                                  boolean mobile, boolean touch) {
        // Playwright does not support isMobile in Firefox. It switches on Firefox's responsive design mode, which lays
        // out a page without a viewport meta tag, such as about:blank, 980px wide; a narrower setViewportSize then
        // waits forever for the window to match. Touch alone already gives Firefox the coarse pointer without hover
        // that the phone layout keys on.
        BrowserContext context = browser(browser).newContext(new Browser.NewContextOptions()
                .setBaseURL(baseUrl).setIsMobile(mobile && !browser.equals("firefox"))
                .setViewportSize(390, 844)
                .setHasTouch(touch)
                .setLocale("en-US"));
        context.setDefaultTimeout(10_000);
        context.tracing().start(new Tracing.StartOptions().setScreenshots(true).setSnapshots(true));
        Path trace = Path.of(System.getProperty("e2e.artifacts", "build/e2e-artifacts"),
                traceName.replaceAll("[^A-Za-z0-9._-]", "_") + "-" + browser + ".zip");
        try {
            List<String> cspViolations = new CopyOnWriteArrayList<>();
            context.exposeBinding("__cspViolation", (source, args) -> cspViolations.add((String) args[0]));
            context.addInitScript("""
                    document.addEventListener("securitypolicyviolation", (e) => window.__cspViolation(
                            e.violatedDirective + " " + (e.blockedURI || "inline") + " at " + e.sourceFile + ":" + e.lineNumber));
                    navigator.serviceWorker?.addEventListener("message", (e) => {
                        if (e.data?.type === "%s") window.__cspViolation(e.data.text);
                    });
                    """.formatted(SERVICE_WORKER_CSP_MESSAGE));
            Page page = context.newPage();
            return new BrowserSession(context, page, trace, BrowserCoverage.start(page, browser, trace), cspViolations);
        } catch (RuntimeException e) {
            context.close();
            throw e;
        }
    }
}

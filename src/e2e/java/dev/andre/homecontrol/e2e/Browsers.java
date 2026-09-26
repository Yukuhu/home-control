package dev.andre.homecontrol.e2e;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Tracing;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

/** One Playwright and one browser per kind for the whole JVM; a fresh context per test. */
public final class Browsers {

    private static Playwright playwright;
    private static final Map<String, Browser> browsers = new HashMap<>();

    private Browsers() {
    }

    public static Stream<String> names() {
        return Arrays.stream(System.getProperty("e2e.browsers", "chromium,webkit").split(","))
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
        BrowserContext context = browser(browser).newContext(new Browser.NewContextOptions()
                .setBaseURL(baseUrl).setIsMobile(mobile)
                .setViewportSize(390, 844)
                .setHasTouch(true)
                .setLocale("en-US"));
        context.setDefaultTimeout(10_000);
        context.tracing().start(new Tracing.StartOptions().setScreenshots(true).setSnapshots(true));
        Path trace = Path.of(System.getProperty("e2e.artifacts", "build/e2e-artifacts"),
                traceName.replaceAll("[^A-Za-z0-9._-]", "_") + "-" + browser + ".zip");
        return new BrowserSession(context, context.newPage(), trace);
    }
}

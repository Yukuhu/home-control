package dev.andre.homecontrol.e2e;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Tracing;

import java.nio.file.Path;
import java.util.List;

/**
 * One browser context and its page for one test; closing saves the Playwright trace, and fails the test when the
 * Content-Security-Policy blocked anything on the pages it visited.
 */
public record BrowserSession(BrowserContext context, Page page, Path trace, BrowserCoverage coverage,
                             List<String> cspViolations) implements AutoCloseable {

    @Override
    public void close() {
        try (context; coverage) {
            context.tracing().stop(new Tracing.StopOptions().setPath(trace));
        }
        if (!cspViolations.isEmpty()) {
            throw new AssertionError("The Content-Security-Policy blocked " + cspViolations);
        }
    }
}

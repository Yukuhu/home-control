package dev.andre.homecontrol.e2e;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Tracing;

import java.nio.file.Path;

/** One browser context and its page for one test; closing saves the Playwright trace. */
public record BrowserSession(BrowserContext context, Page page, Path trace) implements AutoCloseable {

    @Override
    public void close() {
        try {
            context.tracing().stop(new Tracing.StopOptions().setPath(trace));
        } finally {
            context.close();
        }
    }
}

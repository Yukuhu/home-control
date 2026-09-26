package dev.andre.homecontrol.e2e;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.microsoft.playwright.CDPSession;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Chromium's executed V8 ranges, retained across navigations for the LCOV report. */
final class BrowserCoverage implements AutoCloseable {
    private final CDPSession client;
    private final Path output;
    private final Map<String, String> sources = new HashMap<>();

    private BrowserCoverage(Page page, Path output) {
        this.client = page.context().newCDPSession(page);
        this.output = output;
        client.on("Debugger.scriptParsed", this::captureSource);
        client.send("Profiler.enable");
        JsonObject options = new JsonObject();
        options.addProperty("callCount", true);
        options.addProperty("detailed", true);
        client.send("Profiler.startPreciseCoverage", options);
        client.send("Debugger.enable");
        JsonObject pauses = new JsonObject();
        pauses.addProperty("skip", true);
        client.send("Debugger.setSkipAllPauses", pauses);
    }

    static BrowserCoverage start(Page page, String browser, Path trace) {
        if (!browser.equals("chromium") || !Boolean.getBoolean("e2e.coverage")) return null;
        Path directory = Path.of(System.getProperty("e2e.coverage.dir", "build/coverage/browser-raw"));
        return new BrowserCoverage(page, directory.resolve(trace.getFileName() + ".json"));
    }

    private void captureSource(JsonObject event) {
        String url = event.get("url").getAsString();
        if (!url.startsWith("http://") && !url.startsWith("https://")) return;
        String path = URI.create(url).getPath();
        if (!path.startsWith("/js/") && !path.equals("/sw.js")) return;
        String scriptId = event.get("scriptId").getAsString();
        JsonObject parameters = new JsonObject();
        parameters.addProperty("scriptId", scriptId);
        try {
            sources.put(scriptId, client.send("Debugger.getScriptSource", parameters).get("scriptSource").getAsString());
        } catch (PlaywrightException _) {
            // Navigation may discard a script before its source can be retrieved; never invent coverage for it.
        }
    }

    @Override
    public void close() {
        try {
            JsonArray entries = new JsonArray();
            for (var element : client.send("Profiler.takePreciseCoverage").getAsJsonArray("result")) {
                JsonObject entry = element.getAsJsonObject();
                String source = sources.get(entry.get("scriptId").getAsString());
                if (source != null) {
                    entry.addProperty("source", source);
                    entries.add(entry);
                }
            }
            client.send("Profiler.stopPreciseCoverage");
            client.send("Profiler.disable");
            client.send("Debugger.disable");
            Files.createDirectories(output.getParent());
            Files.writeString(output, entries.toString());
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write browser coverage", e);
        } finally {
            client.detach();
        }
    }
}

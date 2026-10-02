package dev.andre.homecontrol.e2e;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.microsoft.playwright.CDPSession;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.List;

/** Chromium's executed V8 ranges, retained across navigations for the LCOV report. */
final class BrowserCoverage implements AutoCloseable {
    private final CDPSession client;
    private final Page page;
    private final Map<String, WorkerCoverage> workers = new HashMap<>();
    private final Path output;
    private final Map<String, String> sources = new HashMap<>();

    private BrowserCoverage(Page page, Path output) {
        this.page = page;
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
        client.on("Target.receivedMessageFromTarget", event -> {
            WorkerCoverage worker = workers.get(event.get("sessionId").getAsString());
            if (worker != null) worker.receive(JsonParser.parseString(event.get("message").getAsString()).getAsJsonObject());
        });
        client.on("Target.attachedToTarget", event -> {
            String session = event.get("sessionId").getAsString();
            WorkerCoverage worker = new WorkerCoverage(session);
            workers.put(session, worker);
            worker.start();
        });
        client.on("Target.detachedFromTarget", event -> {
            WorkerCoverage worker = workers.get(event.get("sessionId").getAsString());
            if (worker != null) worker.detached = true;
        });
        JsonObject attach = new JsonObject();
        attach.addProperty("autoAttach", true);
        attach.addProperty("waitForDebuggerOnStart", true);
        // Nested sessions can be addressed with the public CDPSession API's sendMessageToTarget method.
        attach.addProperty("flatten", false);
        attach.add("filter", JsonParser.parseString("[{\"type\":\"service_worker\"},{\"exclude\":true}]"));
        client.send("Target.setAutoAttach", attach);
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

    /** A service worker has its own V8 isolate, which the page profiler cannot observe. */
    private final class WorkerCoverage {
        private final String session;
        private final Map<Integer, JsonObject> responses = new HashMap<>();
        private final Set<String> scripts = new HashSet<>();
        private int nextId;
        private boolean detached;

        private WorkerCoverage(String session) {
            this.session = session;
        }

        private void start() {
            try {
                send("Profiler.enable", new JsonObject());
                JsonObject options = new JsonObject();
                options.addProperty("callCount", true);
                options.addProperty("detailed", true);
                send("Profiler.startPreciseCoverage", options);
                send("Debugger.enable", new JsonObject());
            } finally {
                send("Runtime.runIfWaitingForDebugger", new JsonObject());
            }
        }

        private void receive(JsonObject message) {
            if (message.has("id")) {
                responses.put(message.get("id").getAsInt(), message);
            } else if ("Debugger.scriptParsed".equals(message.get("method").getAsString())) {
                JsonObject script = message.getAsJsonObject("params");
                String url = script.get("url").getAsString();
                if ((url.startsWith("http://") || url.startsWith("https://"))
                        && URI.create(url).getPath().equals("/sw.js")) {
                    scripts.add(script.get("scriptId").getAsString());
                }
            }
        }

        private JsonObject send(String method, JsonObject parameters) {
            int id = ++nextId;
            JsonObject message = new JsonObject();
            message.addProperty("id", id);
            message.addProperty("method", method);
            message.add("params", parameters);
            JsonObject envelope = new JsonObject();
            envelope.addProperty("sessionId", session);
            envelope.addProperty("message", message.toString());
            client.send("Target.sendMessageToTarget", envelope);
            page.waitForCondition(() -> detached || responses.containsKey(id));
            if (detached) throw new IllegalStateException("Service worker detached while collecting coverage");
            JsonObject response = responses.remove(id);
            if (response.has("error")) throw new IllegalStateException("Worker coverage " + response.get("error"));
            return response.getAsJsonObject("result");
        }

        private JsonArray finish() {
            // A terminated isolate has no readable counters; leave its execution uncovered.
            if (detached) return new JsonArray();
            try {
                return collect();
            } catch (PlaywrightException error) {
                if (detached || error.getMessage().contains("No session with given id")
                        || error.getMessage().contains("Session with given id not found")) return new JsonArray();
                throw error;
            } catch (IllegalStateException error) {
                if (detached) return new JsonArray();
                throw error;
            }
        }

        private JsonArray collect() {
            JsonArray entries = new JsonArray();
            for (var element : send("Profiler.takePreciseCoverage", new JsonObject()).getAsJsonArray("result")) {
                JsonObject entry = element.getAsJsonObject();
                String scriptId = entry.get("scriptId").getAsString();
                if (!scripts.contains(scriptId)) continue;
                JsonObject parameters = new JsonObject();
                parameters.addProperty("scriptId", scriptId);
                entry.addProperty("source", send("Debugger.getScriptSource", parameters).get("scriptSource").getAsString());
                entries.add(entry);
            }
            send("Profiler.stopPreciseCoverage", new JsonObject());
            send("Profiler.disable", new JsonObject());
            send("Debugger.disable", new JsonObject());
            return entries;
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
            for (WorkerCoverage worker : List.copyOf(workers.values())) entries.addAll(worker.finish());
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

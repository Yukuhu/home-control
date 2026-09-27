package dev.andre.homecontrol.web;

import java.io.UncheckedIOException;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

/**
 * Collects an event stream's lines on a virtual thread. An SSE stream never ends by itself, so tests end it with
 * {@code HttpClient.shutdownNow()}; the reader treats that as the end of the stream instead of dying with an uncaught
 * exception, which Awaitility would otherwise report from whichever {@code await()} happens to be running.
 */
final class EventStreamReader {

    private final List<String> lines = new CopyOnWriteArrayList<>();
    private final Thread reader;

    EventStreamReader(HttpResponse<Stream<String>> response) {
        reader = Thread.ofVirtual().name("sse-reader").start(() -> {
            try {
                response.body().forEach(lines::add);
            } catch (UncheckedIOException _) {
                // The client was shut down: the only way these streams end.
            }
        });
    }

    List<String> lines() {
        return lines;
    }

    /** Waits up to {@code timeout} for the stream to end; true if it did. */
    boolean awaitEnd(Duration timeout) throws InterruptedException {
        return reader.join(timeout);
    }
}

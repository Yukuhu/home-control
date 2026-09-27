package dev.andre.homecontrol.sources.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Answers every request with 200 and a body that never ends: one space every 50 ms until the client gives up or
 * the server is closed. Stands in for an upstream that trickles its answer to hold the caller.
 */
public final class TricklingServer implements AutoCloseable {

    private final HttpServer server;
    private final AtomicInteger bytesSent = new AtomicInteger();
    private final AtomicInteger openStreams = new AtomicInteger();
    private volatile boolean closed;

    public TricklingServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", this::trickle);
        server.start();
    }

    public URI url(String path) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }

    /** Bytes written so far across all requests; more than one proves a client read past the headers. */
    public int bytesSent() {
        return bytesSent.get();
    }

    /** Responses still being written; zero once every client has closed its connection. */
    public int openStreams() {
        return openStreams.get();
    }

    // A peer that is slow on purpose is exactly what the deadline tests exercise.
    @SuppressWarnings("java:S2925")
    private void trickle(HttpExchange exchange) {
        openStreams.incrementAndGet();
        try (exchange) {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 0);
            OutputStream body = exchange.getResponseBody();
            while (!closed) {
                body.write(' ');
                body.flush();
                bytesSent.incrementAndGet();
                Thread.sleep(50);
            }
        } catch (IOException _) {
            // The client closed the connection: what a deadline is meant to do.
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        } finally {
            openStreams.decrementAndGet();
        }
    }

    @Override
    public void close() {
        closed = true;
        server.stop(0);
    }
}

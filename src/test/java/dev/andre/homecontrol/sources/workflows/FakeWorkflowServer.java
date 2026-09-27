package dev.andre.homecontrol.sources.workflows;

import com.sun.net.httpserver.HttpHandler;
import dev.andre.homecontrol.testsupport.FakeHttpServer;
import dev.andre.homecontrol.testsupport.Request;
import dev.andre.homecontrol.testsupport.Response;
import dev.andre.homecontrol.testsupport.TestTls;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import static dev.andre.homecontrol.testsupport.FakeHttpServer.ANY_METHOD;

/** Local-only, managed fixture with request recording and deterministic blocking hooks. */
public final class FakeWorkflowServer implements AutoCloseable {

    private final FakeHttpServer server;

    public FakeWorkflowServer() throws IOException {
        this(FakeHttpServer.start());
    }

    private FakeWorkflowServer(FakeHttpServer server) {
        this.server = server;
    }

    /** HTTPS with a self-signed certificate for fixture.invalid, which the fetcher must refuse. */
    static FakeWorkflowServer untrustedHttps() throws IOException {
        return new FakeWorkflowServer(FakeHttpServer.start(TestTls.serverContext("fixture.invalid")));
    }

    public URI url(String path) {
        return server.url(path);
    }

    public void respond(String path, int status, String body) {
        server.respond(ANY_METHOD, path, Response.of(status, "application/json", body));
    }

    void redirect(String path, int status, String location) {
        server.respond(ANY_METHOD, path, Response.empty(status).withHeader("Location", location));
    }

    void block(String path, boolean afterHeaders, CountDownLatch entered, CountDownLatch release) {
        route(path, e -> {
            if (afterHeaders) {
                e.sendResponseHeaders(200, 0);
                e.getResponseBody().write('{');
                e.getResponseBody().flush();
            }
            entered.countDown();
            try { release.await(); } catch (InterruptedException _) { Thread.currentThread().interrupt(); return; }
            if (!afterHeaders) e.sendResponseHeaders(200, 0);
            e.getResponseBody().write('}');
        });
    }

    void route(String path, HttpHandler handler) { server.handle(ANY_METHOD, path, handler); }
    public int count(String path) { return server.count(ANY_METHOD, path); }
    List<Request> requests(String path) { return server.requests(ANY_METHOD, path); }

    @Override public void close() {
        server.close();
    }
}

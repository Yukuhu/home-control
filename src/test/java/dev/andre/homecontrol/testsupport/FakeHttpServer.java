package dev.andre.homecontrol.testsupport;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * An in-process HTTP or HTTPS server on 127.0.0.1 for tests.
 *
 * <p>A route matches on method, path and a predicate over the request; the newest matching route answers, so a test
 * can override a default. A path ending in {@code /**} matches every path that starts with what comes before the
 * {@code **}. Every request is recorded before it is answered. A request no route matches gets the fallback, which is
 * 404 without a body unless changed.
 */
public final class FakeHttpServer implements AutoCloseable {

    /** Matches every method, wherever a method is asked for. */
    public static final String ANY_METHOD = "*";

    private static final Function<Request, Response> NOT_FOUND = request -> Response.empty(404);

    private record Route(String method, String path, Predicate<Request> when, HttpHandler handler) {
        boolean matches(Request request) {
            return methodMatches(method, request.method()) && pathMatches(path, request.path()) && when.test(request);
        }
    }

    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final List<Route> routes = new CopyOnWriteArrayList<>();
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final AtomicInteger bytesTrickled = new AtomicInteger();
    private final AtomicInteger openTrickles = new AtomicInteger();
    private volatile Function<Request, Response> fallback = NOT_FOUND;
    private volatile Duration delay = Duration.ZERO;
    private volatile boolean closed;

    private FakeHttpServer(HttpServer server) {
        this.server = server;
        server.createContext("/", this::dispatch);
        server.setExecutor(executor);
        server.start();
    }

    /** A plain HTTP server on a free port. */
    public static FakeHttpServer start() throws IOException {
        return new FakeHttpServer(HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0));
    }

    /** An HTTPS server on a free port, presenting the certificate of {@code tls} (see {@link TestTls}). */
    public static FakeHttpServer start(SSLContext tls) throws IOException {
        HttpsServer https = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        https.setHttpsConfigurator(new HttpsConfigurator(tls));
        return new FakeHttpServer(https);
    }

    public URI url() {
        String scheme = server instanceof HttpsServer ? "https" : "http";
        return URI.create(scheme + "://127.0.0.1:" + server.getAddress().getPort());
    }

    public URI url(String path) {
        return URI.create(url() + path);
    }

    /** Answers {@code method path} with {@code answers} in order; the last one repeats. */
    public FakeHttpServer respond(String method, String path, Response... answers) {
        return respond(method, path, request -> true, answers);
    }

    /** Like {@link #respond(String, String, Response...)}, for requests {@code when} accepts. */
    public FakeHttpServer respond(String method, String path, Predicate<Request> when, Response... answers) {
        if (answers.length == 0) {
            throw new IllegalArgumentException("A route needs at least one answer");
        }
        Deque<Response> queue = new ArrayDeque<>(List.of(answers));
        return route(method, path, when, exchange -> {
            Response next;
            synchronized (queue) {
                next = queue.size() > 1 ? queue.pollFirst() : queue.peekFirst();
            }
            write(exchange, next);
        });
    }

    /** Lets {@code handler} answer {@code method path}. The server closes the exchange afterwards. */
    public FakeHttpServer handle(String method, String path, HttpHandler handler) {
        return route(method, path, request -> true, handler);
    }

    /** Answers 200 with a JSON body that never ends: one space every 50 ms until the client or the server closes. */
    public FakeHttpServer trickle(String method, String path) {
        return handle(method, path, this::trickle);
    }

    /** What a request no route matches gets, instead of 404 without a body. */
    public FakeHttpServer fallback(Response response) {
        return fallback(request -> response);
    }

    public FakeHttpServer fallback(Function<Request, Response> answer) {
        this.fallback = answer;
        return this;
    }

    /** Every request, whether a route matches or not, waits this long before it is answered. */
    public FakeHttpServer delay(Duration wait) {
        this.delay = wait;
        return this;
    }

    public List<Request> requests() {
        return List.copyOf(requests);
    }

    public List<Request> requests(String method, String path) {
        return requests.stream()
                .filter(request -> methodMatches(method, request.method()) && request.path().equals(path))
                .toList();
    }

    public int count(String method, String path) {
        return requests(method, path).size();
    }

    public Request last(String method, String path) {
        List<Request> matching = requests(method, path);
        if (matching.isEmpty()) {
            throw new AssertionError("No " + method + " " + path + " received; got " + requests);
        }
        return matching.getLast();
    }

    /** Bytes the trickling routes have written; more than one proves a client read past the headers. */
    public int bytesTrickled() {
        return bytesTrickled.get();
    }

    /** Trickling responses still being written; zero once every such client has closed its connection. */
    public int openTrickles() {
        return openTrickles.get();
    }

    /** Forgets every route, request and delay, and restores the 404 fallback. */
    public void reset() {
        routes.clear();
        requests.clear();
        delay = Duration.ZERO;
        fallback = NOT_FOUND;
    }

    /** Stops listening and ends every handler still running, blocked or trickling. Calling it again does nothing. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        server.stop(0);
        executor.shutdownNow();
        executor.close();
    }

    private FakeHttpServer route(String method, String path, Predicate<Request> when, HttpHandler handler) {
        routes.addFirst(new Route(method, path, when, handler));
        return this;
    }

    private void dispatch(HttpExchange exchange) throws IOException {
        try (exchange) {
            Request request = Request.of(exchange);
            requests.add(request);
            pause(delay);
            HttpHandler handler = routes.stream()
                    .filter(route -> route.matches(request))
                    .findFirst()
                    .map(Route::handler)
                    .orElse(unknown -> write(unknown, fallback.apply(request)));
            handler.handle(exchange);
        }
    }

    private static void write(HttpExchange exchange, Response response) throws IOException {
        pause(response.delay());
        if (response.contentType() != null) {
            exchange.getResponseHeaders().set("Content-Type", response.contentType());
        }
        response.headers().forEach(exchange.getResponseHeaders()::set);
        if (response.body().length == 0) {
            exchange.sendResponseHeaders(response.status(), -1);
            return;
        }
        exchange.sendResponseHeaders(response.status(), response.body().length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(response.body());
        }
    }

    // A peer that is slow on purpose is exactly what the deadline tests exercise.
    @SuppressWarnings("java:S2925")
    private void trickle(HttpExchange exchange) {
        openTrickles.incrementAndGet();
        try {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 0);
            OutputStream body = exchange.getResponseBody();
            while (!closed) {
                body.write(' ');
                body.flush();
                bytesTrickled.incrementAndGet();
                Thread.sleep(50);
            }
        } catch (IOException _) {
            // The client closed the connection: what a deadline is meant to do.
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        } finally {
            openTrickles.decrementAndGet();
        }
    }

    // A slow upstream is what the timeout tests exercise, so answering late on purpose is the point.
    @SuppressWarnings("java:S2925")
    private static void pause(Duration wait) {
        if (wait.isZero()) {
            return;
        }
        try {
            Thread.sleep(wait);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean methodMatches(String wanted, String method) {
        return wanted.equals(ANY_METHOD) || wanted.equals(method);
    }

    private static boolean pathMatches(String wanted, String path) {
        return wanted.endsWith("/**") ? path.startsWith(wanted.substring(0, wanted.length() - 2)) : wanted.equals(path);
    }
}

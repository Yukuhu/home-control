package dev.andre.homecontrol.sources.workflows;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Local-only, managed fixture with request recording and deterministic blocking hooks. */
public final class FakeWorkflowServer implements AutoCloseable {
    record Recorded(String path, Map<String, List<String>> headers) {
        String header(String name) {
            var values = headers.get(name.toLowerCase(Locale.ROOT));
            return values == null ? null : String.join(",", values);
        }
    }

    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, HttpHandler> routes = new ConcurrentHashMap<>();
    private final List<Recorded> requests = new CopyOnWriteArrayList<>();

    public FakeWorkflowServer() throws IOException {
        this(HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0));
    }

    private FakeWorkflowServer(HttpServer server) {
        this.server = server;
        server.createContext("/", exchange -> {
            try (exchange) {
                Map<String, List<String>> headers = new ConcurrentHashMap<>();
                exchange.getRequestHeaders().forEach((name, values) -> headers.put(name.toLowerCase(Locale.ROOT), List.copyOf(values)));
                requests.add(new Recorded(exchange.getRequestURI().getPath(), headers));
                routes.getOrDefault(exchange.getRequestURI().getPath(), e -> e.sendResponseHeaders(404, -1)).handle(exchange);
            }
        });
        server.setExecutor(executor);
        server.start();
    }

    static FakeWorkflowServer untrustedHttps() throws Exception {
        var pair = KeyPairGenerator.getInstance("RSA");
        pair.initialize(2048);
        var keys = pair.generateKeyPair();
        var name = new X500Name("CN=fixture.invalid");
        var certificate = new JcaX509CertificateConverter().getCertificate(new JcaX509v3CertificateBuilder(
                name, BigInteger.ONE, Date.from(Instant.now().minusSeconds(60)),
                Date.from(Instant.now().plusSeconds(3600)), name, keys.getPublic())
                .build(new JcaContentSignerBuilder("SHA256withRSA").build(keys.getPrivate())));
        var store = KeyStore.getInstance("PKCS12");
        store.load(null, null);
        store.setKeyEntry("test", keys.getPrivate(), "fixture".toCharArray(), new java.security.cert.Certificate[]{certificate});
        var managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        managers.init(store, "fixture".toCharArray());
        var context = SSLContext.getInstance("TLS");
        context.init(managers.getKeyManagers(), null, null);
        var server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(context));
        return new FakeWorkflowServer(server);
    }

    public URI url(String path) {
        return URI.create((server instanceof HttpsServer ? "https" : "http") + "://127.0.0.1:" + server.getAddress().getPort() + path);
    }

    public void respond(String path, int status, String body) {
        route(path, e -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            e.getResponseHeaders().set("Content-Type", "application/json");
            e.sendResponseHeaders(status, bytes.length);
            e.getResponseBody().write(bytes);
        });
    }

    void redirect(String path, int status, String location) {
        route(path, e -> {
            e.getResponseHeaders().set("Location", location);
            e.sendResponseHeaders(status, -1);
        });
    }

    void block(String path, boolean afterHeaders, CountDownLatch entered, CountDownLatch release) {
        route(path, e -> {
            if (afterHeaders) {
                e.sendResponseHeaders(200, 0);
                e.getResponseBody().write('{');
                e.getResponseBody().flush();
            }
            entered.countDown();
            try { release.await(); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); return; }
            if (!afterHeaders) e.sendResponseHeaders(200, 0);
            e.getResponseBody().write('}');
        });
    }

    void route(String path, HttpHandler handler) { routes.put(path, handler); }
    public int count(String path) { return (int) requests.stream().filter(r -> r.path().equals(path)).count(); }
    List<Recorded> requests(String path) { return requests.stream().filter(r -> r.path().equals(path)).toList(); }

    @Override public void close() {
        server.stop(0);
        executor.shutdownNow();
        executor.close();
    }
}

package dev.andre.homecontrol.sources.http;

import com.sun.net.httpserver.HttpServer;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VettedHttpClientsTest {

    private static final VettedHttpClients.AddressVetting LOOPBACK =
            host -> new InetAddress[] {InetAddress.ofLiteral("127.0.0.1")};

    private HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok", exchange -> {
            requests.incrementAndGet();
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (exchange; OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.createContext("/moved", exchange -> {
            try (exchange) {
                exchange.getResponseHeaders().set("Location", "/ok");
                exchange.sendResponseHeaders(302, -1);
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    /** A host name no DNS knows: a request can only reach the fake through the vetted address. */
    private URI unresolvable(String path) {
        return URI.create("http://calendar.test:" + server.getAddress().getPort() + path);
    }

    @Test
    void connectsToTheAddressesTheVettingReturned() throws IOException {
        List<String> vetted = new CopyOnWriteArrayList<>();
        try (CloseableHttpClient http = VettedHttpClients.create(host -> {
            vetted.add(host);
            return LOOPBACK.addresses(host);
        }, 2, Duration.ofSeconds(1))) {
            String body = http.execute(new HttpGet(unresolvable("/ok")),
                    response -> EntityUtils.toString(response.getEntity()));

            assertThat(body).isEqualTo("ok");
        }
        assertThat(vetted).containsExactly("calendar.test");
    }

    @Test
    void aVettingRefusalStopsTheRequestBeforeItConnects() throws IOException {
        try (CloseableHttpClient http = VettedHttpClients.create(host -> {
            throw new UnknownHostException(host + " is not allowed");
        }, 2, Duration.ofSeconds(1))) {
            HttpGet request = new HttpGet(unresolvable("/ok"));

            assertThatThrownBy(() -> http.execute(request, HttpResponse::getCode))
                    .isInstanceOf(UnknownHostException.class)
                    .hasMessage("calendar.test is not allowed");
        }
        assertThat(requests).hasValue(0);
    }

    @Test
    void redirectsAreReturnedNotFollowed() throws IOException {
        try (CloseableHttpClient http = VettedHttpClients.create(LOOPBACK, 2, Duration.ofSeconds(1))) {
            int status = http.execute(new HttpGet(unresolvable("/moved")), HttpResponse::getCode);

            assertThat(status).isEqualTo(302);
        }
        assertThat(requests).hasValue(0);
    }
}

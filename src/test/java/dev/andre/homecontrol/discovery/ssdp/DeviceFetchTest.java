package dev.andre.homecontrol.discovery.ssdp;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeviceFetchTest {

    private HttpServer server;
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER).build();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/small", exchange -> {
            try (exchange) {
                byte[] body = "<root/>".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.createContext("/chunked-big", exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(200, 0); // chunked: no Content-Length to pre-check
                OutputStream out = exchange.getResponseBody();
                byte[] chunk = new byte[8192];
                for (int i = 0; i < 16; i++) {
                    out.write(chunk);
                }
            } catch (IOException _) {
                // the client hung up once over the cap
            }
        });
        server.createContext("/trickle", DeviceFetchTest::trickle);
        server.createContext("/moved", exchange -> {
            try (exchange) {
                exchange.getResponseHeaders().set("Location", "/small");
                exchange.sendResponseHeaders(302, -1);
            }
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    // A device that drips its description byte by byte: the slowness is what the deadline test needs.
    @SuppressWarnings("java:S2925")
    private static void trickle(HttpExchange exchange) {
        try (exchange) {
            exchange.sendResponseHeaders(200, 0);
            OutputStream out = exchange.getResponseBody();
            for (int i = 0; i < 50; i++) {
                out.write('a');
                out.flush();
                Thread.sleep(100);
            }
        } catch (IOException _) {
            // the client gave up
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    private URI url(String path) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }

    @Test
    void onlyAnHttpIpLiteralOfTheAnnouncingAddressIsSafe() {
        assertThat(DeviceFetch.isSafeToFetch(URI.create("http://10.0.0.9:49152/d.xml"), "10.0.0.9")).isTrue();
        assertThat(DeviceFetch.isSafeToFetch(URI.create("http://10.0.0.8:49152/d.xml"), "10.0.0.9")).isFalse();
        assertThat(DeviceFetch.isSafeToFetch(URI.create("https://10.0.0.9:49152/d.xml"), "10.0.0.9")).isFalse();
        assertThat(DeviceFetch.isSafeToFetch(URI.create("http://speaker.lan:49152/d.xml"), "speaker.lan")).isFalse();
        assertThat(DeviceFetch.isSafeToFetch(URI.create("http://10.0.0.9/d.xml"), "10.0.0.9")).isFalse();
        assertThat(DeviceFetch.isSafeToFetch(URI.create("http://10.0.0.9:49152/d.xml"), (String) null)).isFalse();
    }

    @Test
    void readsASmallBody() throws Exception {
        assertThat(DeviceFetch.get(http, url("/small"), Duration.ofSeconds(2), 1024)).asString(StandardCharsets.UTF_8)
                .isEqualTo("<root/>");
    }

    @Test
    void refusesABodyOverTheCapEvenWithoutContentLength() {
        assertThatThrownBy(() -> DeviceFetch.get(http, url("/chunked-big"), Duration.ofSeconds(2), 64 * 1024))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("cap");
    }

    @Test
    void aTricklingDeviceRunsIntoTheDeadline() {
        assertThatThrownBy(() -> DeviceFetch.get(http, url("/trickle"), Duration.ofMillis(500), 64 * 1024))
                .isInstanceOf(HttpTimeoutException.class)
                .hasMessageContaining("no complete answer within 500 ms");
    }

    @Test
    void redirectsAreNotFollowed() {
        assertThatThrownBy(() -> DeviceFetch.get(http, url("/moved"), Duration.ofSeconds(2), 1024))
                .isInstanceOf(IOException.class)
                .hasMessage("HTTP 302");
    }
}

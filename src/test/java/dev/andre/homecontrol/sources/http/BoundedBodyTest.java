package dev.andre.homecontrol.sources.http;

import com.sun.net.httpserver.HttpServer;
import dev.andre.homecontrol.testsupport.FakeHttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

@Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class BoundedBodyTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    private final HttpClient http = HttpClient.newHttpClient();
    private HttpServer server;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void respond(String path, int status, byte[] body) {
        server.createContext(path, exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
                exchange.getResponseBody().write(body);
            }
        });
    }

    private HttpResponse<byte[]> get(String path, int maxBytes) throws IOException, InterruptedException {
        URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
        return http.send(HttpRequest.newBuilder(uri).timeout(TIMEOUT).build(), BoundedBody.handler(maxBytes, TIMEOUT));
    }

    @Test
    void returnsABodyUpToTheCap() throws Exception {
        respond("/exact", 200, new byte[100]);

        assertThat(get("/exact", 100).body()).hasSize(100);
    }

    @Test
    void keepsOneByteMoreThanTheCapSoTheCallerCanTellItIsTooLarge() throws Exception {
        respond("/large", 200, new byte[5000]);

        assertThat(get("/large", 100).body()).hasSize(101);
    }

    @Test
    void anEmptyBodyIsEmpty() throws Exception {
        respond("/empty", 204, new byte[0]);

        HttpResponse<byte[]> response = get("/empty", 100);

        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(response.body()).isEmpty();
    }

    @Test
    void aBodyThatNeverFinishesFailsAtTheDeadlineAndClosesTheConnection() throws Exception {
        try (FakeHttpServer trickling = FakeHttpServer.start().trickle(FakeHttpServer.ANY_METHOD, "/**")) {
            HttpRequest request = HttpRequest.newBuilder(trickling.url("/feed")).timeout(Duration.ofSeconds(1)).build();

            assertThatThrownBy(() -> http.send(request, BoundedBody.handler(1_000_000, Duration.ofSeconds(1))))
                    .isInstanceOf(HttpTimeoutException.class);
            assertThat(trickling.bytesTrickled()).isGreaterThan(1);
            await().atMost(Duration.ofSeconds(5)).until(() -> trickling.openTrickles() == 0);
        }
    }
}

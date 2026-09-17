package dev.andre.homecontrol.adapters.net;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TextWebSocketTest {

    private final BlockingQueue<String> texts = new LinkedBlockingQueue<>();
    private final BlockingQueue<String> closes = new LinkedBlockingQueue<>();
    private final TextWebSocket.Listener listener = new TextWebSocket.Listener() {
        @Override
        public void onText(String text) {
            texts.add(text);
        }

        @Override
        public void onClosed(String reason) {
            closes.add(reason);
        }
    };

    private FakeWebSocketServer server;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void startEchoServer() throws IOException {
        server = FakeWebSocketServer.plain((connection, text) -> connection.send(text));
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    private TextWebSocket connect() throws IOException {
        return TextWebSocket.connect(http, URI.create(server.url("/")), Duration.ofSeconds(2), listener);
    }

    @Test
    void exchangesTextWithTheServer() throws Exception {
        try (TextWebSocket socket = connect()) {
            socket.send("hello");

            assertThat(texts.poll(5, TimeUnit.SECONDS)).isEqualTo("hello");
        }
    }

    @Test
    void reassemblesLargeMessagesInBothDirections() throws Exception {
        try (TextWebSocket socket = connect()) {
            socket.send("x".repeat(70_000));

            assertThat(texts.poll(5, TimeUnit.SECONDS)).hasSize(70_000);
        }
    }

    @Test
    void reportsTheServerDroppingTheConnectionOnce() throws Exception {
        try (TextWebSocket socket = connect()) {
            server.dropAll();

            assertThat(closes.poll(5, TimeUnit.SECONDS)).isNotNull();
            assertThat(closes.poll(500, TimeUnit.MILLISECONDS)).isNull();
            assertThat(socket.isOpen()).isFalse();
            assertThatThrownBy(() -> socket.send("late")).isInstanceOf(IOException.class);
        }
    }

    @Test
    void closingItDoesNotReportAClose() throws Exception {
        TextWebSocket socket = connect();

        socket.close();

        assertThat(closes.poll(500, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void aClosedPortIsAnIOException() throws IOException {
        URI closed = URI.create("ws://127.0.0.1:" + FakeWebSocketServer.closedPort());

        assertThatThrownBy(() -> TextWebSocket.connect(http, closed, Duration.ofSeconds(2), listener))
                .isInstanceOf(IOException.class);
    }

    @Test
    void errorMessagesNeverContainTheQuery() throws IOException {
        URI closed = URI.create("ws://127.0.0.1:" + FakeWebSocketServer.closedPort() + "/api?token=secret");

        assertThatThrownBy(() -> TextWebSocket.connect(http, closed, Duration.ofSeconds(2), listener))
                .isInstanceOf(IOException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("secret"));
    }
}

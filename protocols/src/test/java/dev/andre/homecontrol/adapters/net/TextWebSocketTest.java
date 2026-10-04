package dev.andre.homecontrol.adapters.net;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
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
        server = FakeWebSocketServer.plain(FakeWebSocketServer.Connection::send);
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
    void aMessageOverTheCapClosesTheConnectionAsAProtocolError() throws Exception {
        try (TextWebSocket socket = connect()) {
            socket.send("x".repeat(TextWebSocket.MAX_MESSAGE_CHARS + 1));

            assertThat(closes.poll(5, TimeUnit.SECONDS)).contains("larger than");
            assertThat(texts.poll(500, TimeUnit.MILLISECONDS)).isNull();
            assertThat(closes.poll(500, TimeUnit.MILLISECONDS)).isNull();
            assertThat(socket.isOpen()).isFalse();
        }
    }

    @Test
    void aMessageAtTheCapIsDelivered() throws Exception {
        try (TextWebSocket socket = connect()) {
            socket.send("x".repeat(TextWebSocket.MAX_MESSAGE_CHARS));

            assertThat(texts.poll(5, TimeUnit.SECONDS)).hasSize(TextWebSocket.MAX_MESSAGE_CHARS);
        }
    }

    @Test
    void aSocketThatOpensAfterTheTimeoutIsAborted() {
        java.util.concurrent.CompletableFuture<java.net.http.WebSocket> opening = new java.util.concurrent.CompletableFuture<>();
        java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicBoolean aborted = new java.util.concurrent.atomic.AtomicBoolean();
        java.net.http.WebSocket late = (java.net.http.WebSocket) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{java.net.http.WebSocket.class}, (proxy, method, args) -> {
                    if (method.getName().equals("abort")) {
                        aborted.set(true);
                    }
                    return null;
                });

        TextWebSocket.abandon(opening, closed);
        opening.complete(late);

        assertThat(aborted).isTrue();
        assertThat(closed).isTrue();
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

    @Test
    void aTvThatNeverFinishesTheHandshakeFailsWithoutTheQuery() throws IOException {
        try (ServerSocket silent = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            URI uri = URI.create("ws://127.0.0.1:" + silent.getLocalPort() + "/api/v2?token=secret");

            assertThatThrownBy(() -> TextWebSocket.connect(http, uri, Duration.ofMillis(200), listener))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("ws://127.0.0.1:" + silent.getLocalPort() + "/api/v2")
                    .satisfies(e -> assertThat(e.getMessage()).doesNotContain("secret"));
        }
        assertThat(closes).isEmpty();
    }

    @Test
    void anInterruptedOpeningGivesUpAndKeepsTheInterrupt() throws IOException {
        try (ServerSocket silent = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            URI uri = URI.create("ws://127.0.0.1:" + silent.getLocalPort() + "/");
            Thread.currentThread().interrupt();
            try {
                assertThatThrownBy(() -> TextWebSocket.connect(http, uri, Duration.ofSeconds(2), listener))
                        .isInstanceOf(IOException.class)
                        .hasMessageStartingWith("Interrupted while opening");
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
            } finally {
                Thread.interrupted();
            }
        }
    }

    @Test
    void binaryFramesAreSkippedAndTheNextTextStillArrives() throws Exception {
        server.close();
        server = FakeWebSocketServer.plain((connection, text) -> {
            connection.sendFrame(0x2, new byte[]{1, 2, 3});
            connection.send(text);
        });
        try (TextWebSocket socket = connect()) {
            socket.send("after the binary frame");

            assertThat(texts.poll(5, TimeUnit.SECONDS)).isEqualTo("after the binary frame");
            assertThat(socket.isOpen()).isTrue();
        }
    }

    @Test
    void aCloseFromTheTvNamesItsCodeAndReason() throws Exception {
        server.close();
        server = FakeWebSocketServer.plain((connection, text) -> connection.sendFrame(0x8,
                new byte[]{0x0F, (byte) 0xA0, 'b', 'y', 'e'}));
        try (TextWebSocket socket = connect()) {
            socket.send("hang up");

            assertThat(closes.poll(5, TimeUnit.SECONDS)).isEqualTo("closed by the device (4000, bye)");
            assertThat(socket.isOpen()).isFalse();
        }
    }

    @Test
    void aCloseWithoutAReasonNamesOnlyItsCode() throws Exception {
        server.close();
        server = FakeWebSocketServer.plain((connection, text) -> connection.closeNormally());
        try (TextWebSocket socket = connect()) {
            socket.send("hang up");

            assertThat(closes.poll(5, TimeUnit.SECONDS)).isEqualTo("closed by the device (1000)");
        }
    }

    @Test
    void aSendTheTvStopsReadingTimesOutAndLosesTheConnection() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        server.close();
        // The fake reads frames on the thread that runs the handler: holding it fills the socket buffers.
        server = FakeWebSocketServer.plain((connection, text) -> awaitQuietly(release));
        try (TextWebSocket socket = TextWebSocket.connect(http, URI.create(server.url("/")),
                Duration.ofMillis(300), listener)) {
            String chunk = "x".repeat(TextWebSocket.MAX_MESSAGE_CHARS);
            IOException failure = null;
            for (int i = 0; i < 128 && failure == null; i++) {
                try {
                    socket.send(chunk);
                } catch (IOException e) {
                    failure = e;
                }
            }

            assertThat(failure).isNotNull().hasMessage("Sending timed out");
            assertThat(closes.poll(5, TimeUnit.SECONDS)).isEqualTo("Sending timed out");
            assertThat(socket.isOpen()).isFalse();
            assertThatThrownBy(() -> socket.send("late")).hasMessage("The connection is closed");
            assertThat(closes.poll(200, TimeUnit.MILLISECONDS)).isNull();
        } finally {
            release.countDown();
        }
    }

    @Test
    void theQueryIsLeftOutOfAnAddressWithoutAPort() {
        assertThat(TextWebSocket.withoutQuery(URI.create("wss://tv.local/api/v2/channels/samsung.remote.control?token=1")))
                .isEqualTo("wss://tv.local/api/v2/channels/samsung.remote.control");
        assertThat(TextWebSocket.withoutQuery(URI.create("ws://10.0.0.5:3000?token=1")))
                .isEqualTo("ws://10.0.0.5:3000");
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }
}

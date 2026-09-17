package dev.andre.homecontrol.adapters.net;

import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLHandshakeException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InsecureTlsTest {

    private final BlockingQueue<String> texts = new LinkedBlockingQueue<>();
    private final TextWebSocket.Listener listener = new TextWebSocket.Listener() {
        @Override
        public void onText(String text) {
            texts.add(text);
        }

        @Override
        public void onClosed(String reason) {
        }
    };

    @Test
    void connectsToATvCertificateIssuedForAnotherHost() throws Exception {
        try (FakeWebSocketServer server = FakeWebSocketServer.tls((connection, text) -> connection.send(text));
             TextWebSocket socket = TextWebSocket.connect(InsecureTls.httpClient(Duration.ofSeconds(2)),
                     URI.create(server.url("/")), Duration.ofSeconds(2), listener)) {
            socket.send("over tls");

            assertThat(texts.poll(5, TimeUnit.SECONDS)).isEqualTo("over tls");
        }
    }

    @Test
    void theDefaultClientStillRejectsThatCertificate() throws Exception {
        try (FakeWebSocketServer server = FakeWebSocketServer.tls((connection, text) -> connection.send(text))) {
            assertThatThrownBy(() -> TextWebSocket.connect(HttpClient.newHttpClient(),
                    URI.create(server.url("/")), Duration.ofSeconds(2), listener))
                    .isInstanceOf(IOException.class)
                    .satisfies(e -> assertThat(causeChainHas(e, SSLHandshakeException.class)).isTrue());
        }
    }

    private static boolean causeChainHas(Throwable error, Class<? extends Throwable> type) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (type.isInstance(cause)) {
                return true;
            }
        }
        return false;
    }
}

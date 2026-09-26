package dev.andre.homecontrol.adapters.net;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A blocking, text-only facade over {@link java.net.http.WebSocket} for the TV protocols. Messages
 * arrive whole (fragments are reassembled); the listener hears a close at most once and never for
 * a {@link #close()} this side initiated. Listener callbacks run on the HTTP client's threads (a
 * close detected by a failed {@link #send} is reported on the sender's thread): never call
 * {@link #send} from inside one.
 */
public final class TextWebSocket implements AutoCloseable {

    public interface Listener {
        void onText(String text);

        void onClosed(String reason);
    }

    /** Largest reassembled message accepted; a TV that sends more is disconnected as a protocol error. */
    static final int MAX_MESSAGE_CHARS = 1024 * 1024;
    private static final int MESSAGE_TOO_BIG = 1009;

    private final WebSocket socket;
    private final Duration timeout;
    private final AtomicBoolean closed;
    private final Listener listener;

    private TextWebSocket(WebSocket socket, Duration timeout, AtomicBoolean closed, Listener listener) {
        this.socket = socket;
        this.timeout = timeout;
        this.closed = closed;
        this.listener = listener;
    }

    public static TextWebSocket connect(HttpClient client, URI uri, Duration timeout, Listener listener)
            throws IOException {
        AtomicBoolean closed = new AtomicBoolean();
        WebSocket.Listener adapter = new TextListener(closed, listener);
        CompletableFuture<WebSocket> opening = client.newWebSocketBuilder()
                .connectTimeout(timeout)
                .buildAsync(uri, adapter);
        try {
            WebSocket socket = opening.get(timeout.toMillis() * 2, TimeUnit.MILLISECONDS);
            return new TextWebSocket(socket, timeout, closed, listener);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            throw new IOException("Could not open " + withoutQuery(uri) + ": " + cause.getClass().getSimpleName(), cause);
        } catch (TimeoutException e) {
            abandon(opening, closed);
            throw new IOException("Timed out opening " + withoutQuery(uri), e);
        } catch (InterruptedException e) {
            abandon(opening, closed);
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while opening " + withoutQuery(uri), e);
        }
    }

    private static final class TextListener implements WebSocket.Listener {
        private final AtomicBoolean closed;
        private final Listener listener;
        private final StringBuilder partial = new StringBuilder();

        private TextListener(AtomicBoolean closed, Listener listener) {
            this.closed = closed;
            this.listener = listener;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            acceptText(webSocket, data, last);
            return null;
        }

        private void acceptText(WebSocket webSocket, CharSequence data, boolean last) {
            if (closed.get()) return;
            if (partial.length() + data.length() > MAX_MESSAGE_CHARS) {
                // A TV's messages are small; a runaway one must not grow the heap. Protocol error.
                partial.setLength(0);
                if (closed.compareAndSet(false, true)) {
                    webSocket.sendClose(MESSAGE_TOO_BIG, "")
                            .orTimeout(1, TimeUnit.SECONDS)
                            .whenComplete((ignored, error) -> webSocket.abort());
                    listener.onClosed("the device sent a message larger than " + MAX_MESSAGE_CHARS + " characters");
                }
                return;
            }
            partial.append(data);
            if (last) {
                String text = partial.toString();
                partial.setLength(0);
                listener.onText(text);
            }
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            if (closed.compareAndSet(false, true)) {
                listener.onClosed("closed by the device (" + statusCode
                        + (reason == null || reason.isEmpty() ? "" : ", " + reason) + ")");
            }
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            if (closed.compareAndSet(false, true)) {
                // The class name only: JDK connection exceptions can echo the full URI and its query.
                listener.onClosed(error.getClass().getSimpleName());
            }
        }
    }

    /** Nobody will use a socket that finishes opening after we gave up: abort it and stay silent. */
    static void abandon(CompletableFuture<WebSocket> opening, AtomicBoolean closed) {
        closed.set(true);
        opening.thenAccept(WebSocket::abort);
    }

    /**
     * Sends one complete text message; one sender at a time (the JDK forbids overlapping sends).
     * A failed or stuck send means the connection is gone: when the peer drops the socket while a
     * send is in flight the JDK reports that only through the send, never to the listener, so this
     * aborts the socket and reports the close itself (on the calling thread).
     */
    public synchronized void send(String text) throws IOException {
        if (closed.get()) {
            throw new IOException("The connection is closed");
        }
        try {
            socket.sendText(text, true).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            String reason = "Sending failed: " + e.getCause().getClass().getSimpleName();
            lost(reason);
            throw new IOException(reason, e.getCause());
        } catch (TimeoutException e) {
            lost("Sending timed out");
            throw new IOException("Sending timed out", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while sending", e);
        }
    }

    private void lost(String reason) {
        if (closed.compareAndSet(false, true)) {
            socket.abort();
            listener.onClosed(reason);
        }
    }

    public boolean isOpen() {
        return !closed.get() && !socket.isOutputClosed() && !socket.isInputClosed();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "")
                    .orTimeout(1, TimeUnit.SECONDS)
                    .whenComplete((ignored, error) -> socket.abort());
        }
    }

    /** Tizen puts its token in the query string; it must never reach a log or an error message. */
    public static String withoutQuery(URI uri) {
        return uri.getScheme() + "://" + uri.getHost() + (uri.getPort() < 0 ? "" : ":" + uri.getPort())
                + (uri.getRawPath() == null ? "" : uri.getRawPath());
    }
}

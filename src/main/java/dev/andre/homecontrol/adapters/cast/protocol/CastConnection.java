package dev.andre.homecontrol.adapters.cast.protocol;

import dev.andre.homecontrol.adapters.cast.protocol.channel.CastMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.node.ObjectNode;

import javax.net.ssl.SSLSocket;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/**
 * One CASTV2 channel: TLS, framing, the platform virtual connection, heartbeat, and
 * request/reply correlation. Knows nothing about devices or state.
 *
 * <p>Threads: a reader thread delivers every non-heartbeat message to the {@link Listener}
 * and completes waiters; a heartbeat thread pings. Listener callbacks run on the reader thread
 * and must not block — in particular they must never call {@link #request} or
 * {@link Waiter#await}, which wait for that same thread.
 */
public final class CastConnection implements AutoCloseable {

    public interface Listener {
        void onMessage(CastIncoming message);

        /** Called at most once, never after {@link #close()} by the owner. */
        void onDisconnected(CastDisconnectCause cause);
    }

    private static final Logger log = LoggerFactory.getLogger(CastConnection.class);
    private static final int CONNECT_TIMEOUT_MILLIS = 5_000;

    private final SSLSocket socket;
    private final CastFraming framing;
    private final Listener listener;
    private final AtomicInteger requestIds = new AtomicInteger(1);
    private final List<Waiter> waiters = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService heartbeat;
    private volatile boolean closed;

    /**
     * Connects, opens the virtual connection to {@code receiver-0}, and starts heartbeat and
     * reader. {@code staleTimeout} is the socket read timeout: with a ping every
     * {@code heartbeatInterval} a healthy receiver always sends something sooner.
     */
    public static CastConnection open(String host, int port, Duration heartbeatInterval, Duration staleTimeout,
                                      Listener listener) throws IOException {
        if (staleTimeout.compareTo(heartbeatInterval) <= 0) {
            throw new IllegalArgumentException("The stale timeout must be longer than the heartbeat interval");
        }
        SSLSocket socket = CastTls.connect(host, port, CONNECT_TIMEOUT_MILLIS, Math.toIntExact(staleTimeout.toMillis()));
        try {
            return new CastConnection(socket, heartbeatInterval, listener);
        } catch (IOException | RuntimeException e) {
            try {
                socket.close();
            } catch (IOException _) {
                // Already failing.
            }
            throw e;
        }
    }

    private CastConnection(SSLSocket socket, Duration heartbeatInterval, Listener listener) throws IOException {
        this.socket = socket;
        this.listener = listener;
        this.framing = new CastFraming(socket.getInputStream(), socket.getOutputStream());
        write(CastNamespaces.CONNECTION, CastNamespaces.PLATFORM_RECEIVER_ID, CastPayloads.connect());
        this.heartbeat = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("cast-heartbeat").factory());
        long every = heartbeatInterval.toMillis();
        heartbeat.scheduleWithFixedDelay(this::ping, every, every, TimeUnit.MILLISECONDS);
        Thread.ofVirtual().name("cast-reader").start(this::readLoop);
    }

    /** Opens a virtual connection to an app transport (required before talking to it). */
    public void connect(String destinationId) throws IOException {
        send(CastNamespaces.CONNECTION, destinationId, CastPayloads.connect());
    }

    public void disconnect(String destinationId) throws IOException {
        send(CastNamespaces.CONNECTION, destinationId, CastPayloads.close());
    }

    public void send(String namespace, String destinationId, ObjectNode payload) throws IOException {
        if (closed) {
            throw new IOException("The Cast connection is closed");
        }
        write(namespace, destinationId, payload);
    }

    public int nextRequestId() {
        return requestIds.getAndIncrement();
    }

    /** Sends {@code payload} with a fresh {@code requestId} and waits for the message echoing it. */
    public CastIncoming request(String namespace, String destinationId, ObjectNode payload, Duration timeout)
            throws IOException {
        int requestId = nextRequestId();
        payload.put("requestId", requestId);
        Waiter waiter = expect(message -> message.requestId() == requestId);
        try {
            send(namespace, destinationId, payload);
            return waiter.await(timeout);
        } finally {
            waiter.cancel();
        }
    }

    /** Registers interest BEFORE sending, so a fast reply cannot slip past. */
    public Waiter expect(Predicate<CastIncoming> match) {
        Waiter waiter = new Waiter(match);
        waiters.add(waiter);
        if (closed) {
            waiter.future.completeExceptionally(new IOException("The Cast connection is closed"));
        }
        return waiter;
    }

    public final class Waiter {

        private final Predicate<CastIncoming> match;
        private final CompletableFuture<CastIncoming> future = new CompletableFuture<>();

        private Waiter(Predicate<CastIncoming> match) {
            this.match = match;
        }

        public CastIncoming await(Duration timeout) throws IOException {
            try {
                return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException _) {
                throw new CastTimeoutException("The Cast receiver did not answer within " + timeout.toMillis() + " ms");
            } catch (ExecutionException e) {
                throw e.getCause() instanceof IOException io ? io : new IOException(e.getCause());
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("Interrupted while waiting for the Cast receiver");
            } finally {
                cancel();
            }
        }

        public void cancel() {
            waiters.remove(this);
        }
    }

    private void ping() {
        try {
            send(CastNamespaces.HEARTBEAT, CastNamespaces.PLATFORM_RECEIVER_ID, CastPayloads.ping());
        } catch (IOException | RuntimeException e) {
            log.debug("Cast heartbeat failed: {}", e.getMessage()); // the reader notices the drop
        }
    }

    private void readLoop() {
        try {
            CastMessage message;
            while (!closed && (message = framing.read()) != null) {
                dispatch(message);
            }
            finish(CastDisconnectCause.CLOSED);
        } catch (SocketTimeoutException _) {
            finish(CastDisconnectCause.STALE);
        } catch (IOException | RuntimeException _) {
            finish(CastDisconnectCause.ERROR);
        }
    }

    private void dispatch(CastMessage message) throws IOException {
        if (message.getPayloadType() != CastMessage.PayloadType.STRING) {
            return; // binary namespaces (device auth) are not used
        }
        CastIncoming incoming;
        try {
            incoming = new CastIncoming(message.getNamespace(), message.getSourceId(), message.getDestinationId(),
                    CastPayloads.parse(message.getPayloadUtf8()));
        } catch (JacksonException _) {
            log.debug("Ignoring a Cast message with an unreadable payload on {}", message.getNamespace());
            return;
        }
        if (CastNamespaces.HEARTBEAT.equals(incoming.namespace())) {
            if ("PING".equals(incoming.type())) {
                send(CastNamespaces.HEARTBEAT, incoming.sourceId(), CastPayloads.pong());
            }
            return;
        }
        if (CastNamespaces.CONNECTION.equals(incoming.namespace()) && "CLOSE".equals(incoming.type())
                && CastNamespaces.PLATFORM_RECEIVER_ID.equals(incoming.sourceId())) {
            finish(CastDisconnectCause.CLOSED);
            return;
        }
        for (Waiter waiter : waiters) {
            if (waiter.match.test(incoming)) {
                waiter.future.complete(incoming);
            }
        }
        try {
            listener.onMessage(incoming);
        } catch (RuntimeException e) {
            log.warn("A Cast message listener failed", e);
        }
    }

    private void finish(CastDisconnectCause cause) {
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
        }
        shutdown();
        listener.onDisconnected(cause);
    }

    /** Closes quietly: best-effort CLOSE to the receiver, no listener callback. */
    @Override
    public void close() {
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
        }
        try {
            write(CastNamespaces.CONNECTION, CastNamespaces.PLATFORM_RECEIVER_ID, CastPayloads.close());
        } catch (IOException | RuntimeException _) {
            // Leaving anyway.
        }
        shutdown();
    }

    private void shutdown() {
        heartbeat.shutdownNow();
        try {
            socket.close();
        } catch (IOException _) {
            // Already gone.
        }
        IOException lost = new IOException("The Cast connection was closed");
        waiters.forEach(waiter -> waiter.future.completeExceptionally(lost));
    }

    private void write(String namespace, String destinationId, ObjectNode payload) throws IOException {
        framing.write(CastMessage.newBuilder()
                .setProtocolVersion(CastMessage.ProtocolVersion.CASTV2_1_0)
                .setSourceId(CastNamespaces.SENDER_ID)
                .setDestinationId(destinationId)
                .setNamespace(namespace)
                .setPayloadType(CastMessage.PayloadType.STRING)
                .setPayloadUtf8(CastPayloads.toJson(payload))
                .build());
    }
}

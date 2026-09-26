package dev.andre.homecontrol.adapters.bluetooth.player;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** mpv's JSON IPC over its Unix socket: one JSON object per line, replies matched by request_id. */
public final class MpvIpc implements AutoCloseable {

    public interface EventListener {
        void onEvent(JsonNode event);

        void onClosed();
    }

    private static final Logger log = LoggerFactory.getLogger(MpvIpc.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int MAX_LINE_BYTES = 1 << 20;

    private final SocketChannel channel;
    private final EventListener listener;
    private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final AtomicLong nextId = new AtomicLong(1);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object writeLock = new Object();

    private MpvIpc(SocketChannel channel, EventListener listener) {
        this.channel = channel;
        this.listener = listener;
        Thread.ofVirtual().name("mpv-ipc").start(this::readLoop);
    }

    /** Waits until mpv created its socket (shortly after start), unless the process died first. */
    public static MpvIpc connect(Path socket, Duration timeout, BooleanSupplier processAlive, EventListener listener)
            throws IOException {
        long deadline = System.nanoTime() + timeout.toNanos();
        IOException last = null;
        while (System.nanoTime() < deadline) {
            if (!processAlive.getAsBoolean()) {
                throw new IOException("mpv exited before opening its control socket");
            }
            if (Files.exists(socket)) {
                SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX);
                try {
                    channel.connect(UnixDomainSocketAddress.of(socket));
                    return new MpvIpc(channel, listener);
                } catch (IOException e) {
                    channel.close();
                    last = e;
                }
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("interrupted while waiting for mpv");
            }
        }
        if (!processAlive.getAsBoolean()) {
            throw new IOException("mpv exited before opening its control socket");
        }
        throw new IOException("mpv did not open its control socket within " + timeout.toMillis() + " ms", last);
    }

    public JsonNode command(Duration timeout, Object... command) throws IOException, MpvException {
        ObjectNode request = JSON.createObjectNode();
        ArrayNode arguments = request.putArray("command");
        for (Object argument : command) {
            switch (argument) {
                case String text -> arguments.add(text);
                case Integer number -> arguments.add(number);
                case Long number -> arguments.add(number);
                case Double number -> arguments.add(number);
                case Boolean flag -> arguments.add(flag);
                default -> throw new IllegalArgumentException("Unsupported mpv argument type " + argument.getClass().getSimpleName());
            }
        }
        String name = String.valueOf(command[0]);
        long id = nextId.getAndIncrement();
        request.put("request_id", id);
        CompletableFuture<JsonNode> answer = new CompletableFuture<>();
        pending.put(id, answer);
        try {
            if (closed.get()) {
                throw new IOException("mpv control socket is closed");
            }
            ByteBuffer line = ByteBuffer.wrap((JSON.writeValueAsString(request) + "\n").getBytes(StandardCharsets.UTF_8));
            synchronized (writeLock) {
                while (line.hasRemaining()) {
                    channel.write(line);
                }
            }
            JsonNode response = answer.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            String error = response.path("error").asString("success");
            if (!"success".equals(error)) {
                throw MpvException.refused(name, error);
            }
            return response.path("data");
        } catch (TimeoutException _) {
            throw new IOException("mpv did not answer " + name + " within " + timeout.toMillis() + " ms");
        } catch (ExecutionException e) {
            throw new IOException("mpv control socket closed while waiting for " + name, e.getCause());
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("interrupted while waiting for mpv");
        } finally {
            pending.remove(id);
        }
    }

    public boolean open() {
        return !closed.get();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            try {
                channel.close();
            } catch (IOException _) {
                // Close is best effort; pending commands are still failed below.
            }
            IOException gone = new IOException("mpv control socket is closed");
            pending.values().forEach(waiter -> waiter.completeExceptionally(gone));
            try {
                listener.onClosed();
            } catch (RuntimeException e) {
                log.debug("mpv close listener failed", e);
            }
        }
    }

    /** Splits a channel into UTF-8 lines until end of stream. Shared with the test fake. */
    static void readLines(SocketChannel channel, Consumer<String> lines) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(8192);
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        while (channel.read(buffer) >= 0) {
            buffer.flip();
            while (buffer.hasRemaining()) {
                byte next = buffer.get();
                if (next == '\n') {
                    lines.accept(line.toString(StandardCharsets.UTF_8));
                    line.reset();
                } else if (line.size() >= MAX_LINE_BYTES) {
                    throw new IOException("mpv sent a line longer than 1 MiB");
                } else {
                    line.write(next);
                }
            }
            buffer.clear();
        }
    }

    private void readLoop() {
        try {
            readLines(channel, this::dispatch);
        } catch (IOException e) {
            log.debug("mpv control socket ended: {}", e.toString());
        } finally {
            close();
        }
    }

    private void dispatch(String text) {
        JsonNode message;
        try {
            message = JSON.readTree(text);
        } catch (JacksonException _) {
            return;
        }
        if (message.has("event")) {
            try {
                listener.onEvent(message);
            } catch (RuntimeException e) {
                log.debug("mpv event listener failed", e);
            }
            return;
        }
        JsonNode id = message.path("request_id");
        if (id.isNumber()) {
            CompletableFuture<JsonNode> waiter = pending.get(id.asLong(-1));
            if (waiter != null) {
                waiter.complete(message);
            }
        }
    }
}

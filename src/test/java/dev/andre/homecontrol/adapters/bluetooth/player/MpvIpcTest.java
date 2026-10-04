package dev.andre.homecontrol.adapters.bluetooth.player;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class MpvIpcTest {

    @TempDir
    Path dir;

    private FakeMpv fake;
    private final List<JsonNode> events = new CopyOnWriteArrayList<>();

    private MpvIpc.EventListener listener() {
        return new MpvIpc.EventListener() {
            @Override
            public void onEvent(JsonNode event) {
                events.add(event);
            }

            @Override
            public void onClosed() {
                // Only events are recorded; aClosedSocketFailsPendingRequestsAndReportsClosed tracks closing.
            }
        };
    }

    @BeforeEach
    void setUp() throws IOException {
        fake = FakeMpv.serve(dir.resolve("s.sock"), FakeMpv.Options.defaults().withMetadataTitle("Meta Song"), 50, line -> { });
    }

    @AfterEach
    void tearDown() {
        fake.close();
    }

    @Test
    void answersRequestsById() throws Exception {
        MpvIpc ipc = MpvIpc.connect(socket(), Duration.ofSeconds(1), () -> true, listener());
        try {
            assertThat(ipc.command(Duration.ofSeconds(1), "get_property", "volume").asDouble(-1)).isEqualTo(50.0);
            ipc.command(Duration.ofSeconds(1), "set_property", "volume", 30);
            assertThat(fake.volume()).isEqualTo(30.0);
            assertThat(ipc.command(Duration.ofSeconds(1), "get_property", "idle-active").asBoolean(false)).isTrue();
        } finally {
            ipc.close();
        }
    }

    @Test
    void concurrentRequestsGetTheirOwnAnswers() throws Exception {
        MpvIpc ipc = MpvIpc.connect(socket(), Duration.ofSeconds(1), () -> true, listener());
        try {
            int threads = 20;
            CountDownLatch done = new CountDownLatch(threads);
            AtomicInteger failures = new AtomicInteger();
            for (int i = 0; i < threads; i++) {
                boolean volume = i % 2 == 0;
                Thread.ofVirtual().start(() -> {
                    try {
                        JsonNode result = ipc.command(Duration.ofSeconds(2), "get_property", volume ? "volume" : "pause");
                        if (volume ? !result.isNumber() : !result.isBoolean()) {
                            failures.incrementAndGet();
                        }
                    } catch (Exception _) {
                        failures.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(failures.get()).isZero();
        } finally {
            ipc.close();
        }
    }

    @Test
    void refusalsAreMpvExceptions() throws Exception {
        MpvIpc ipc = MpvIpc.connect(socket(), Duration.ofSeconds(1), () -> true, listener());
        try {
            assertThatThrownBy(() -> ipc.command(Duration.ofSeconds(1), "get_property", "time-pos"))
                    .isInstanceOf(MpvException.class)
                    .satisfies(e -> assertThat(((MpvException) e).error()).isEqualTo("property unavailable"))
                    .hasMessage("mpv refused get_property: property unavailable");
            assertThatThrownBy(() -> ipc.command(Duration.ofSeconds(1), "get_property", "nope"))
                    .isInstanceOf(MpvException.class)
                    .satisfies(e -> assertThat(((MpvException) e).error()).isEqualTo("property not found"));
        } finally {
            ipc.close();
        }
    }

    @Test
    void eventsReachTheListener() throws Exception {
        MpvIpc ipc = MpvIpc.connect(socket(), Duration.ofSeconds(1), () -> true, listener());
        try {
            ipc.command(Duration.ofSeconds(1), "loadfile", "http://nas/a.mp3", "replace");
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
                List<String> names = events.stream().map(e -> e.path("event").asString("")).toList();
                assertThat(names).contains("start-file", "file-loaded");
                assertThat(names.indexOf("start-file")).isLessThan(names.indexOf("file-loaded"));
            });
        } finally {
            ipc.close();
        }
    }

    @Test
    void aSilentServerTimesOut() throws Exception {
        Path silentSocket = dir.resolve("silent.sock");
        ServerSocketChannel silent = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        silent.bind(UnixDomainSocketAddress.of(silentSocket));
        Thread.ofVirtual().start(() -> {
            try {
                silent.accept();
            } catch (IOException _) {
                // Thrown once the test closes the server socket; this server never answers anyway.
            }
        });
        try {
            MpvIpc ipc = MpvIpc.connect(silentSocket, Duration.ofSeconds(1), () -> true, listener());
            try {
                assertThatThrownBy(() -> ipc.command(Duration.ofMillis(300), "get_property", "volume"))
                        .isInstanceOf(IOException.class)
                        .hasMessage("mpv did not answer get_property within 300 ms");
            } finally {
                ipc.close();
            }
        } finally {
            silent.close();
        }
    }

    @Test
    void waitsForTheSocketToAppear() throws Exception {
        Path delayed = dir.resolve("delayed.sock");
        CountDownLatch retrying = new CountDownLatch(1);
        AtomicInteger attempts = new AtomicInteger();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var connection = executor.submit(() -> MpvIpc.connect(delayed, Duration.ofSeconds(3), () -> {
                if (attempts.incrementAndGet() == 2) retrying.countDown();
                return true;
            }, listener()));
            try {
                assertThat(retrying.await(2, TimeUnit.SECONDS)).as("Connection retries while the socket is absent").isTrue();
                assertThat(connection.isDone()).isFalse();
                try (FakeMpv delayedServer = FakeMpv.serve(delayed, FakeMpv.Options.defaults(), 50, line -> { });
                     MpvIpc ipc = connection.get(2, TimeUnit.SECONDS)) {
                    assertThat(ipc.command(Duration.ofSeconds(1), "get_property", "volume").asDouble(-1))
                            .isEqualTo(delayedServer.volume());
                }
            } finally {
                connection.cancel(true);
            }
        }
    }

    @Test
    void givesUpWhenTheProcessDied() {
        Path missing = dir.resolve("missing.sock");
        assertThatThrownBy(() -> MpvIpc.connect(missing, Duration.ofSeconds(2), () -> false, listener()))
                .isInstanceOf(IOException.class).hasMessage("mpv exited before opening its control socket");
        assertThatThrownBy(() -> MpvIpc.connect(missing, Duration.ofMillis(300), () -> true, listener()))
                .isInstanceOf(IOException.class).hasMessageContaining("did not open its control socket");
    }

    @Test
    void aClosedSocketFailsPendingRequestsAndReportsClosed() throws Exception {
        Path silentSocket = dir.resolve("silent2.sock");
        ServerSocketChannel silent = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        silent.bind(UnixDomainSocketAddress.of(silentSocket));
        SocketChannel[] accepted = new SocketChannel[1];
        Thread.ofVirtual().start(() -> {
            try {
                accepted[0] = silent.accept();
            } catch (IOException _) {
                // Left null: the await on accepted[0] below then fails the test.
            }
        });
        java.util.concurrent.atomic.AtomicBoolean closedCalled = new java.util.concurrent.atomic.AtomicBoolean();
        MpvIpc.EventListener trackingListener = new MpvIpc.EventListener() {
            @Override
            public void onEvent(JsonNode event) {
                // Only the close notification matters here.
            }

            @Override
            public void onClosed() {
                closedCalled.set(true);
            }
        };
        try {
            MpvIpc ipc = MpvIpc.connect(silentSocket, Duration.ofSeconds(2), () -> true, trackingListener);
            await().atMost(Duration.ofSeconds(2)).until(() -> accepted[0] != null);

            java.util.concurrent.CompletableFuture<Object> pending = new java.util.concurrent.CompletableFuture<>();
            Thread.ofVirtual().start(() -> {
                try {
                    ipc.command(Duration.ofSeconds(5), "get_property", "volume");
                } catch (Exception e) {
                    pending.complete(e);
                }
            });
            accepted[0].close();

            await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(pending).isDone());
            assertThat(pending.get()).isInstanceOf(IOException.class);
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(closedCalled.get()).isTrue());
            assertThat(ipc.open()).isFalse();
            assertThatThrownBy(() -> ipc.command(Duration.ofSeconds(1), "get_property", "volume"))
                    .isInstanceOf(IOException.class).hasMessage("mpv control socket is closed");
        } finally {
            silent.close();
        }
    }

    @Test
    void neverSendsGarbageForUnknownArgumentTypes() throws Exception {
        MpvIpc ipc = MpvIpc.connect(socket(), Duration.ofSeconds(1), () -> true, listener());
        try {
            Duration timeout = Duration.ofSeconds(1);
            Object unsupported = new Object();
            assertThatThrownBy(() -> ipc.command(timeout, "set_property", "volume", unsupported))
                    .isInstanceOf(IllegalArgumentException.class);
        } finally {
            ipc.close();
        }
    }

    @Test
    void sendsWholeAndDecimalNumbers() throws Exception {
        MpvIpc ipc = MpvIpc.connect(socket(), Duration.ofSeconds(1), () -> true, listener());
        try {
            ipc.command(Duration.ofSeconds(1), "set_property", "volume", 30L);
            assertThat(fake.volume()).isEqualTo(30.0);
            ipc.command(Duration.ofSeconds(1), "set_property", "volume", 42.5);
            assertThat(fake.volume()).isEqualTo(42.5);
        } finally {
            ipc.close();
        }
    }

    @Test
    void aProcessThatDiesWhileTheWaitRunsOutIsReportedAsExited() {
        Path missing = dir.resolve("never.sock");
        Duration timeout = Duration.ofMillis(200);
        long aliveUntil = System.nanoTime() + timeout.toNanos();

        assertThatThrownBy(() -> MpvIpc.connect(missing, timeout, () -> System.nanoTime() < aliveUntil, listener()))
                .isInstanceOf(IOException.class)
                .hasMessage("mpv exited before opening its control socket");
    }

    @Test
    void linesThatAreNotJsonAndAFailingListenerAreSkipped() throws Exception {
        Path rawSocket = dir.resolve("raw.sock");
        List<String> heard = new CopyOnWriteArrayList<>();
        MpvIpc.EventListener failingOnce = new MpvIpc.EventListener() {
            @Override
            public void onEvent(JsonNode event) {
                heard.add(event.path("event").asString(""));
                if (heard.size() == 1) {
                    throw new IllegalStateException("listener bug");
                }
            }

            @Override
            public void onClosed() {
                throw new IllegalStateException("close listener bug");
            }
        };
        try (var _ = rawMpv(rawSocket, channel -> write(channel,
                "not json\n{\"event\":\"start-file\"}\n{\"request_id\":\"x\"}\n{\"event\":\"idle\"}\n"))) {
            MpvIpc ipc = MpvIpc.connect(rawSocket, Duration.ofSeconds(2), () -> true, failingOnce);

            await().atMost(Duration.ofSeconds(2)).until(() -> heard.size() == 2);
            assertThat(heard).containsExactly("start-file", "idle");
            ipc.close();
            assertThat(ipc.open()).isFalse();
        }
    }

    @Test
    void aLineOverOneMebibyteEndsTheConnection() throws Exception {
        Path rawSocket = dir.resolve("long.sock");
        CountDownLatch closed = new CountDownLatch(1);
        MpvIpc.EventListener closing = new MpvIpc.EventListener() {
            @Override
            public void onEvent(JsonNode event) {
                // Nothing complete arrives.
            }

            @Override
            public void onClosed() {
                closed.countDown();
            }
        };
        try (var _ = rawMpv(rawSocket, channel -> write(channel, "x".repeat(1024 * 1024 + 1)))) {
            MpvIpc ipc = MpvIpc.connect(rawSocket, Duration.ofSeconds(2), () -> true, closing);

            assertThat(closed.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(ipc.open()).isFalse();
        }
    }

    /** A control socket that runs {@code script} on the first connection and then keeps it open. */
    private static ServerSocketChannel rawMpv(Path path, Consumer<SocketChannel> script)
            throws IOException {
        ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        server.bind(UnixDomainSocketAddress.of(path));
        Thread.ofVirtual().start(() -> {
            try {
                script.accept(server.accept());
            } catch (IOException _) {
                // The test closed the server.
            }
        });
        return server;
    }

    private static void write(SocketChannel channel, String text) {
        try {
            ByteBuffer bytes = ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
            while (bytes.hasRemaining()) {
                channel.write(bytes);
            }
        } catch (IOException _) {
            // The client went away.
        }
    }

    private Path socket() {
        return dir.resolve("s.sock");
    }
}

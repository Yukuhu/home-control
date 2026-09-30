package dev.andre.homecontrol.sources.jellyfin;

import dev.andre.homecontrol.core.content.ContentSourceException;
import dev.andre.homecontrol.testsupport.FakeHttpServer;
import dev.andre.homecontrol.testsupport.Response;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/** How transport failures and unusable answers are named, and how an {@link JellyfinClient.Image} compares. */
class JellyfinClientFailuresTest {

    private final JellyfinClient client = new JellyfinClient(new JellyfinProperties(true, Duration.ofSeconds(2),
            Duration.ofSeconds(1), 20, Duration.ofSeconds(30)), "0.8.0");
    private FakeJellyfinServer fake;

    @AfterEach
    void closeFake() {
        if (fake != null) {
            fake.close();
        }
    }

    private static JellyfinConnection connection(URI server) {
        return new JellyfinConnection(server, "tok", "dev", "user");
    }

    @Test
    void forbiddenIsUnauthorizedAndAnUnreadableAnswerIsABadResponse() throws IOException {
        fake = new FakeJellyfinServer();
        fake.respondJson("GET", "/forbidden", 403, "{}");
        fake.respondJson("GET", "/garbled", 200, "{not json");

        assertThatThrownBy(() -> client.get(connection(fake.url()), "/forbidden", Map.of()))
                .isInstanceOfSatisfying(JellyfinException.class,
                        e -> assertThat(e.kind()).isEqualTo(ContentSourceException.Kind.UNAUTHORIZED));
        assertThatThrownBy(() -> client.get(connection(fake.url()), "/garbled", Map.of()))
                .isInstanceOfSatisfying(JellyfinException.class,
                        e -> assertThat(e.kind()).isEqualTo(ContentSourceException.Kind.BAD_RESPONSE))
                .hasMessage("Jellyfin at " + fake.url() + " sent an unreadable answer");
    }

    @Test
    void aServerThatNeverAnswersIsNamedAsSuch() throws IOException {
        try (ServerSocket silent = new ServerSocket(0)) {
            URI server = URI.create("http://127.0.0.1:" + silent.getLocalPort());

            assertThatThrownBy(() -> client.get(connection(server), "/x", Map.of()))
                    .isInstanceOfSatisfying(JellyfinException.class,
                            e -> assertThat(e.kind()).isEqualTo(ContentSourceException.Kind.UNREACHABLE))
                    .hasMessageContaining("(request timed out)");
        }
    }

    @Test
    void aServerThatHangsUpIsNamedByTheFailure() throws IOException {
        try (ServerSocket rude = new ServerSocket(0)) {
            Thread.ofVirtual().start(() -> {
                while (!rude.isClosed()) {
                    try (Socket accepted = rude.accept()) {
                        accepted.setSoLinger(true, 0);
                    } catch (IOException _) {
                        return;
                    }
                }
            });
            URI server = URI.create("http://127.0.0.1:" + rude.getLocalPort());

            assertThatThrownBy(() -> client.image(server, "abc", "Primary", null, 480))
                    .isInstanceOfSatisfying(JellyfinException.class,
                            e -> assertThat(e.kind()).isEqualTo(ContentSourceException.Kind.UNREACHABLE))
                    .hasMessageStartingWith("Could not reach Jellyfin at 127.0.0.1 (");
        }
    }

    @Test
    void anInterruptedCallerKeepsItsInterruptAndIsToldSo() throws IOException {
        try (ServerSocket silent = new ServerSocket(0)) {
            URI server = URI.create("http://127.0.0.1:" + silent.getLocalPort());
            Thread.currentThread().interrupt();
            try {
                assertThatThrownBy(() -> client.publicInfo(server))
                        .isInstanceOfSatisfying(JellyfinException.class,
                                e -> assertThat(e.kind()).isEqualTo(ContentSourceException.Kind.UNREACHABLE))
                        .hasMessageContaining("(request interrupted)");
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
            } finally {
                Thread.interrupted();
            }
        }
    }

    @Test
    void anImageComparesByItsBytesAndPrintsOnlyTheirCount() {
        var image = new JellyfinClient.Image("image/jpeg", new byte[] {1, 2, 3});
        var same = new JellyfinClient.Image("image/jpeg", new byte[] {1, 2, 3});

        assertThat(image).isEqualTo(same).hasSameHashCodeAs(same)
                .isNotEqualTo(new JellyfinClient.Image("image/jpeg", new byte[] {1, 2, 4}))
                .isNotEqualTo(new JellyfinClient.Image("image/png", new byte[] {1, 2, 3}))
                .isNotEqualTo("image/jpeg")
                .hasToString("Image[contentType=image/jpeg, bytes=3 bytes]");
        assertThat(new JellyfinClient.Image("image/png", null)).hasToString("Image[contentType=image/png, bytes=none]")
                .isEqualTo(new JellyfinClient.Image("image/png", null));
    }

    @Test
    void noFailureRevealsTheTokenOrThePath() throws IOException {
        int closedPort;
        try (var socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        try (FakeHttpServer server = FakeHttpServer.start()) {
            server.respond("GET", "/secret-path/large", Response.of(200, "application/json", "x".repeat(2 * 1024 * 1024 + 1)));
            server.respond("GET", "/secret-path/gzip", Response.of(200, "application/json", "{}").withHeader("Content-Encoding", "gzip"));
            server.trickle("GET", "/secret-path/slow");
            var secret = new JellyfinConnection(server.url(), "secret-token", "dev", "user");
            var closed = new JellyfinConnection(URI.create("http://127.0.0.1:" + closedPort), "secret-token", "dev", "user");
            for (Runnable call : new Runnable[] {
                    () -> client.get(secret, "/secret-path/large", Map.of("q", "secret-query")),
                    () -> client.get(secret, "/secret-path/gzip", Map.of("q", "secret-query")),
                    () -> client.get(secret, "/secret-path/slow", Map.of("q", "secret-query")),
                    () -> client.get(closed, "/secret-path/x", Map.of("q", "secret-query"))}) {
                JellyfinException failure = catchThrowableOfType(JellyfinException.class, call::run);

                assertThat(failure).hasNoCause();
                assertThat(failure.getMessage()).doesNotContain("secret");
            }
            assertThat(catchThrowableOfType(JellyfinException.class,
                    () -> client.get(closed, "/x", Map.of()))).hasMessageEndingWith(
                    ". Check the address and that Home Control can reach it.");
        }
    }
}

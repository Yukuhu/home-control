package dev.andre.homecontrol.sources.jellyfin;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** How transport failures and unusable answers are named, and how an {@link JellyfinClient.Image} compares. */
class JellyfinClientFailuresTest {

    private final JellyfinClient client = new JellyfinClient(new JellyfinProperties(true, 2, 1, 20), "0.8.0");
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
                        e -> assertThat(e.kind()).isEqualTo(JellyfinException.Kind.UNAUTHORIZED));
        assertThatThrownBy(() -> client.get(connection(fake.url()), "/garbled", Map.of()))
                .isInstanceOfSatisfying(JellyfinException.class,
                        e -> assertThat(e.kind()).isEqualTo(JellyfinException.Kind.BAD_RESPONSE))
                .hasMessage("Jellyfin at " + fake.url() + " sent an unreadable answer");
    }

    @Test
    void aServerThatNeverAnswersIsNamedAsSuch() throws IOException {
        try (ServerSocket silent = new ServerSocket(0)) {
            URI server = URI.create("http://127.0.0.1:" + silent.getLocalPort());

            assertThatThrownBy(() -> client.get(connection(server), "/x", Map.of()))
                    .isInstanceOfSatisfying(JellyfinException.class,
                            e -> assertThat(e.kind()).isEqualTo(JellyfinException.Kind.UNREACHABLE))
                    .hasMessageContaining("(no answer in time)");
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
                            e -> assertThat(e.kind()).isEqualTo(JellyfinException.Kind.UNREACHABLE))
                    .hasMessageStartingWith("Could not reach Jellyfin at " + server + " (");
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
                                e -> assertThat(e.kind()).isEqualTo(JellyfinException.Kind.UNREACHABLE))
                        .hasMessageContaining("(interrupted)");
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
                .isNotEqualTo("image/jpeg");
        assertThat(image).hasToString("Image[contentType=image/jpeg, bytes=3 bytes]");
        assertThat(new JellyfinClient.Image("image/png", null)).hasToString("Image[contentType=image/png, bytes=none]")
                .isEqualTo(new JellyfinClient.Image("image/png", null));
    }
}

package dev.andre.homecontrol.sources.http;

import dev.andre.homecontrol.sources.jellyfin.JellyfinClient;
import dev.andre.homecontrol.sources.jellyfin.JellyfinConnection;
import dev.andre.homecontrol.sources.jellyfin.JellyfinException;
import dev.andre.homecontrol.sources.jellyfin.JellyfinProperties;
import dev.andre.homecontrol.sources.sports.SportsProperties;
import dev.andre.homecontrol.sources.sports.thesportsdb.TheSportsDbClient;
import dev.andre.homecontrol.sources.sports.thesportsdb.TheSportsDbException;
import dev.andre.homecontrol.sources.tmdb.TmdbClient;
import dev.andre.homecontrol.sources.tmdb.TmdbCredential;
import dev.andre.homecontrol.sources.tmdb.TmdbException;
import dev.andre.homecontrol.sources.tmdb.TmdbProperties;
import dev.andre.homecontrol.sources.youtube.YouTubeException;
import dev.andre.homecontrol.sources.youtube.YouTubeHttp;
import dev.andre.homecontrol.sources.youtube.YouTubeProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Every source's HTTP client gives up at its request timeout on a body that never finishes. */
@Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class SlowBodyDeadlineTest {

    private TricklingServer server;

    @BeforeEach
    void start() throws IOException {
        server = new TricklingServer();
    }

    @AfterEach
    void stop() {
        server.close();
    }

    @Test
    void tmdb() {
        TmdbClient client = new TmdbClient(new TmdbProperties(true, server.url("/3"), null, 1, 1, 20, 40,
                Duration.ofHours(24), Duration.ofHours(24), null));
        TmdbCredential key = TmdbCredential.parse("0123456789abcdef0123456789abcdef");

        assertThatThrownBy(() -> client.get(key, "/trending/movie/week", Map.of()))
                .isInstanceOf(TmdbException.class)
                .hasFieldOrPropertyWithValue("kind", TmdbException.Kind.UNREACHABLE);
        assertThat(server.bytesSent()).isGreaterThan(1);
    }

    @Test
    void theSportsDb() {
        TheSportsDbClient client = new TheSportsDbClient(new SportsProperties.TheSportsDb(true,
                server.url("/api/v1/json"), "123", Duration.ofHours(24), 1, 1, null));

        assertThatThrownBy(() -> client.get("123", "eventsnextleague.php", Map.of("id", "4331")))
                .isInstanceOf(TheSportsDbException.class)
                .hasFieldOrPropertyWithValue("kind", TheSportsDbException.Kind.UNREACHABLE)
                .hasMessage("Could not reach TheSportsDB");
        assertThat(server.bytesSent()).isGreaterThan(1);
    }

    @Test
    void jellyfin() {
        JellyfinClient client = new JellyfinClient(new JellyfinProperties(true, 1, 1, 20));
        JellyfinConnection connection = new JellyfinConnection(server.url(""), "tok", "dev", "user");
        URI serverUrl = server.url("");

        assertThatThrownBy(() -> client.get(connection, "/Items", Map.of()))
                .isInstanceOf(JellyfinException.class)
                .hasFieldOrPropertyWithValue("kind", JellyfinException.Kind.UNREACHABLE)
                .hasMessageContaining("(no answer in time)");
        assertThatThrownBy(() -> client.image(serverUrl, "abc", "Primary", null, 480))
                .isInstanceOf(JellyfinException.class)
                .hasFieldOrPropertyWithValue("kind", JellyfinException.Kind.UNREACHABLE);
        assertThat(server.bytesSent()).isGreaterThan(1);
    }

    @Test
    void youtube() {
        URI base = server.url("");
        YouTubeHttp http = new YouTubeHttp(new YouTubeProperties(true, base, base, base, base, 1, 1, 10000, 20, 30,
                30, 5, Duration.ofHours(24), 20, Duration.ofMinutes(60), Duration.ofMinutes(15), Duration.ofHours(6)));
        URI subscriptions = server.url("/youtube/v3/subscriptions");

        assertThatThrownBy(() -> http.get(subscriptions, Map.of()))
                .isInstanceOf(YouTubeException.class)
                .hasFieldOrPropertyWithValue("kind", YouTubeException.Kind.UNREACHABLE)
                .hasMessage("Could not reach 127.0.0.1");
        assertThat(server.bytesSent()).isGreaterThan(1);
    }
}

package dev.andre.homecontrol.sources.http;

import dev.andre.homecontrol.core.content.ContentSourceException;
import dev.andre.homecontrol.sources.jellyfin.JellyfinClient;
import dev.andre.homecontrol.sources.jellyfin.JellyfinConnection;
import dev.andre.homecontrol.sources.jellyfin.JellyfinException;
import dev.andre.homecontrol.sources.jellyfin.JellyfinProperties;
import dev.andre.homecontrol.sources.sports.SportsProperties;
import dev.andre.homecontrol.sources.sports.calendar.CalendarFetchException;
import dev.andre.homecontrol.sources.sports.calendar.CalendarFetcher;
import dev.andre.homecontrol.sources.sports.calendar.CalendarUrlPolicy;
import dev.andre.homecontrol.sources.sports.thesportsdb.TheSportsDbClient;
import dev.andre.homecontrol.sources.sports.thesportsdb.TheSportsDbException;
import dev.andre.homecontrol.sources.tmdb.TmdbClient;
import dev.andre.homecontrol.sources.tmdb.TmdbCredential;
import dev.andre.homecontrol.sources.tmdb.TmdbException;
import dev.andre.homecontrol.sources.tmdb.TmdbProperties;
import dev.andre.homecontrol.sources.workflows.WorkflowException;
import dev.andre.homecontrol.sources.workflows.WorkflowHttpClient;
import dev.andre.homecontrol.sources.workflows.WorkflowProperties;
import dev.andre.homecontrol.sources.youtube.YouTubeException;
import dev.andre.homecontrol.sources.youtube.YouTubeHttp;
import dev.andre.homecontrol.sources.youtube.YouTubeProperties;
import dev.andre.homecontrol.testsupport.FakeHttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Every source's HTTP client gives up at its request timeout on a body that never finishes. */
@Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class SlowBodyDeadlineTest {

    private FakeHttpServer server;

    @BeforeEach
    void start() throws IOException {
        server = FakeHttpServer.start().trickle(FakeHttpServer.ANY_METHOD, "/**");
    }

    @AfterEach
    void stop() {
        server.close();
    }

    @Test
    void tmdb() {
        TmdbClient client = new TmdbClient(new TmdbProperties(true, server.url("/3"), null, Duration.ofSeconds(1),
                Duration.ofSeconds(1), 20, 40,
                Duration.ofHours(24), Duration.ofHours(24), null));
        TmdbCredential key = TmdbCredential.parse("0123456789abcdef0123456789abcdef");

        assertThatThrownBy(() -> client.get(key, "/trending/movie/week", Map.of()))
                .isInstanceOf(TmdbException.class)
                .hasFieldOrPropertyWithValue("kind", ContentSourceException.Kind.UNREACHABLE);
        assertThat(server.bytesTrickled()).isGreaterThan(1);
    }

    @Test
    void theSportsDb() {
        TheSportsDbClient client = new TheSportsDbClient(new SportsProperties.TheSportsDb(true,
                server.url("/api/v1/json"), "123", Duration.ofHours(24), Duration.ofSeconds(1),
                Duration.ofSeconds(1), null));
        Map<String, String> league = Map.of("id", "4331");

        assertThatThrownBy(() -> client.get("123", "eventsnextleague.php", league))
                .isInstanceOf(TheSportsDbException.class)
                .hasFieldOrPropertyWithValue("kind", ContentSourceException.Kind.UNREACHABLE)
                .hasMessage("Could not reach TheSportsDB");
        assertThat(server.bytesTrickled()).isGreaterThan(1);
    }

    @Test
    void jellyfin() {
        JellyfinClient client = new JellyfinClient(new JellyfinProperties(true, Duration.ofSeconds(1),
                Duration.ofSeconds(1), 20, Duration.ofSeconds(30)));
        JellyfinConnection connection = new JellyfinConnection(server.url(""), "tok", "dev", "user");
        URI serverUrl = server.url("");

        assertThatThrownBy(() -> client.get(connection, "/Items", Map.of()))
                .isInstanceOf(JellyfinException.class)
                .hasFieldOrPropertyWithValue("kind", ContentSourceException.Kind.UNREACHABLE)
                .hasMessageContaining("(no answer in time)");
        assertThatThrownBy(() -> client.image(serverUrl, "abc", "Primary", null, 480))
                .isInstanceOf(JellyfinException.class)
                .hasFieldOrPropertyWithValue("kind", ContentSourceException.Kind.UNREACHABLE);
        assertThat(server.bytesTrickled()).isGreaterThan(1);
    }

    @Test
    void youtube() {
        URI base = server.url("");
        YouTubeHttp http = new YouTubeHttp(new YouTubeProperties(true, base, base, base, base, Duration.ofSeconds(1),
                Duration.ofSeconds(1), 10000, 20, 30,
                30, 5, Duration.ofHours(24), 20, Duration.ofMinutes(60), Duration.ofMinutes(15), Duration.ofHours(6)));
        URI subscriptions = server.url("/youtube/v3/subscriptions");

        assertThatThrownBy(() -> http.get(subscriptions, Map.of()))
                .isInstanceOf(YouTubeException.class)
                .hasFieldOrPropertyWithValue("kind", ContentSourceException.Kind.UNREACHABLE)
                .hasMessage("Could not reach 127.0.0.1");
        assertThat(server.bytesTrickled()).isGreaterThan(1);
    }

    @Test
    void calendars() {
        URI calendar = server.url("/cal.ics");
        try (CalendarFetcher fetcher = new CalendarFetcher(
                new SportsProperties.Calendar(Duration.ofHours(6), Duration.ofSeconds(1), Duration.ofSeconds(1),
                5 * 1024 * 1024, 3, true),
                new CalendarUrlPolicy(true))) {
            assertThatThrownBy(() -> fetcher.fetch(calendar))
                    .isInstanceOf(CalendarFetchException.class)
                    .hasFieldOrPropertyWithValue("kind", ContentSourceException.Kind.UNREACHABLE)
                    .hasMessage("Could not reach 127.0.0.1");
        }
        assertThat(server.bytesTrickled()).isGreaterThan(1);
    }

    @Test
    void workflows() {
        URI data = server.url("/data.json");
        try (WorkflowHttpClient client = new WorkflowHttpClient(
                new WorkflowProperties(true, true, Duration.ofSeconds(1), Duration.ofSeconds(1), 4, 2_097_152, 3),
                new OutboundAddressPolicy(true, host -> new InetAddress[] {InetAddress.ofLiteral("127.0.0.1")}))) {
            var request = new WorkflowHttpClient.Request(data.toString(), List.of());
            assertThatThrownBy(() -> client.fetch(request))
                    .isInstanceOf(WorkflowException.class)
                    .hasFieldOrPropertyWithValue("stage", WorkflowException.Stage.FETCH)
                    .hasFieldOrPropertyWithValue("detail", "request timed out");
        }
        assertThat(server.bytesTrickled()).isGreaterThan(1);
    }
}

package dev.andre.homecontrol.sources.sports.calendar;

import dev.andre.homecontrol.core.content.ContentSourceException;
import dev.andre.homecontrol.sources.http.OutboundAddressPolicy;
import dev.andre.homecontrol.sources.sports.SportsProperties;
import dev.andre.homecontrol.testsupport.FakeHttpServer;
import dev.andre.homecontrol.testsupport.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/** Transport failures and malformed redirects while fetching a calendar. */
class CalendarFetcherFailureTest {

    private final SportsProperties.Calendar properties =
            new SportsProperties.Calendar(Duration.ofHours(6), Duration.ofSeconds(1), Duration.ofSeconds(2),
            2 * 1024 * 1024, 3, false);
    private final OutboundAddressPolicy policy = new OutboundAddressPolicy(true);
    private FakeCalendarServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.close();
        }
        Thread.interrupted();
    }

    @Test
    void aRedirectWithoutLocationIsABadResponse() throws IOException {
        server = new FakeCalendarServer();
        server.respond("/moved.ics", 302, "text/plain", "");
        try (CalendarFetcher fetcher = new CalendarFetcher(properties, policy)) {
            URI url = server.url("/moved.ics");

            assertThatThrownBy(() -> fetcher.fetch(url))
                    .isInstanceOf(CalendarFetchException.class)
                    .hasFieldOrPropertyWithValue("kind", ContentSourceException.Kind.BAD_RESPONSE)
                    .hasMessage("The calendar at 127.0.0.1 sent a response Home Control cannot read (redirect has no destination)");
        }
    }

    @Test
    void anInterruptedFetchIsUnreachableKeepsTheInterruptAndConnectsNowhere() throws IOException {
        server = new FakeCalendarServer();
        server.respond("/cal.ics", 200, "text/calendar", "BEGIN:VCALENDAR\nEND:VCALENDAR\n");
        URI url = server.url("/cal.ics");
        try (CalendarFetcher fetcher = new CalendarFetcher(properties, policy)) {
            Thread.currentThread().interrupt();

            assertThatThrownBy(() -> fetcher.fetch(url))
                    .isInstanceOf(CalendarFetchException.class)
                    .hasFieldOrPropertyWithValue("kind", ContentSourceException.Kind.UNREACHABLE)
                    .hasMessage("Could not reach the calendar at 127.0.0.1 (request interrupted)");
            assertThat(Thread.interrupted()).as("the interrupt is kept (and cleared here)").isTrue();
        }
        assertThat(server.count("/cal.ics")).isZero();
    }

    @Test
    void aBodyCutShortIsUnreachable() throws IOException {
        try (ServerSocket truncating = new ServerSocket(0, 1, InetAddress.ofLiteral("127.0.0.1"))) {
            Thread.ofVirtual().start(() -> {
                try (Socket accepted = truncating.accept()) {
                    accepted.getOutputStream().write("""
                            HTTP/1.1 200 OK\r
                            Content-Type: text/calendar\r
                            Content-Length: 100\r
                            \r
                            BEGIN:VCAL""".getBytes(StandardCharsets.US_ASCII));
                    accepted.getOutputStream().flush();
                } catch (IOException _) {
                    // The test is over.
                }
            });
            URI url = URI.create("http://127.0.0.1:" + truncating.getLocalPort() + "/private/token-abc123/cal.ics");
            try (CalendarFetcher fetcher = new CalendarFetcher(properties, policy)) {
                assertThatThrownBy(() -> fetcher.fetch(url))
                        .isInstanceOf(CalendarFetchException.class)
                        .hasFieldOrPropertyWithValue("kind", ContentSourceException.Kind.UNREACHABLE)
                        .hasMessage("Could not reach the calendar at 127.0.0.1 (request failed)")
                        .hasNoCause();
            }
        }
    }

    @Test
    void noFailureRevealsTheSecretLink() throws IOException {
        int closedPort;
        try (var socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        var small = new SportsProperties.Calendar(Duration.ofHours(6), Duration.ofSeconds(1), Duration.ofMillis(800),
                1_024, 2, true);
        OutboundAddressPolicy.Resolver named = host -> new InetAddress[] {InetAddress.ofLiteral("127.0.0.1")};
        try (FakeHttpServer fake = FakeHttpServer.start();
             CalendarFetcher fetcher = new CalendarFetcher(small, new OutboundAddressPolicy(true, named));
             CalendarFetcher refusing = new CalendarFetcher(small, new OutboundAddressPolicy(false, named))) {
            fake.respond("GET", "/secret-path/large.ics", Response.of(200, "text/calendar", "x".repeat(2_000)));
            fake.respond("GET", "/secret-path/gzip.ics", Response.of(200, "text/calendar", "x").withHeader("Content-Encoding", "gzip"));
            fake.respond("GET", "/secret-path/loop.ics", Response.empty(302).withHeader("Location", "/secret-path/loop.ics?token=secret-token"));
            fake.trickle("GET", "/secret-path/slow.ics");
            String base = "http://calendar.test:" + fake.url().getPort() + "/secret-path/";
            String query = "?token=secret-token";
            for (Runnable call : new Runnable[] {
                    () -> fetcher.fetch(URI.create(base + "large.ics" + query)),
                    () -> fetcher.fetch(URI.create(base + "gzip.ics" + query)),
                    () -> fetcher.fetch(URI.create(base + "loop.ics" + query)),
                    () -> fetcher.fetch(URI.create(base + "slow.ics" + query)),
                    () -> fetcher.fetch(URI.create("http://calendar.test:" + closedPort + "/secret-path/cal.ics" + query)),
                    () -> refusing.fetch(URI.create(base + "large.ics" + query))}) {
                CalendarFetchException failure = catchThrowableOfType(CalendarFetchException.class, call::run);

                assertThat(failure).hasNoCause();
                assertThat(failure.getMessage()).doesNotContain("secret");
            }
        }
    }
}

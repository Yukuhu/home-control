package dev.andre.homecontrol.sources.sports.calendar;

import dev.andre.homecontrol.core.content.ContentSourceException;
import dev.andre.homecontrol.sources.sports.SportsProperties;
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

/** Transport failures and malformed redirects while fetching a calendar. */
class CalendarFetcherFailureTest {

    private final SportsProperties.Calendar properties =
            new SportsProperties.Calendar(Duration.ofHours(6), Duration.ofSeconds(1), Duration.ofSeconds(2),
            2 * 1024 * 1024, 3, false);
    private final CalendarUrlPolicy policy = new CalendarUrlPolicy(true);
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
                    .hasMessage("127.0.0.1 answered HTTP 302");
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
                    .hasMessage("Could not reach 127.0.0.1");
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
                        .hasMessage("Could not reach 127.0.0.1")
                        .hasCauseInstanceOf(IOException.class);
            }
        }
    }
}

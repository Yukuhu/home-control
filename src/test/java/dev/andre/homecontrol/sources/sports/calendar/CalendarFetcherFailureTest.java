package dev.andre.homecontrol.sources.sports.calendar;

import dev.andre.homecontrol.sources.sports.SportsProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/** Transport failures and malformed redirects while fetching a calendar. */
class CalendarFetcherFailureTest {

    private static final URI CALENDAR = URI.create("http://127.0.0.1:9/private/token-abc123/cal.ics");

    private final SportsProperties.Calendar properties =
            new SportsProperties.Calendar(Duration.ofHours(6), 1, 2, 2 * 1024 * 1024, 3, false);
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
        CalendarFetcher fetcher = new CalendarFetcher(properties, policy);
        URI url = server.url("/moved.ics");

        assertThatThrownBy(() -> fetcher.fetch(url))
                .isInstanceOf(CalendarFetchException.class)
                .hasFieldOrPropertyWithValue("kind", CalendarFetchException.Kind.BAD_RESPONSE)
                .hasMessage("127.0.0.1 answered HTTP 302");
    }

    @Test
    void anInterruptedFetchIsUnreachableAndKeepsTheInterrupt() throws Exception {
        HttpClient client = mock(HttpClient.class);
        given(client.send(any(), any())).willThrow(new InterruptedException("stop"));
        CalendarFetcher fetcher = new CalendarFetcher(properties, policy, client);

        assertThatThrownBy(() -> fetcher.fetch(CALENDAR))
                .isInstanceOf(CalendarFetchException.class)
                .hasFieldOrPropertyWithValue("kind", CalendarFetchException.Kind.UNREACHABLE)
                .hasMessage("Could not reach 127.0.0.1");
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void aBrokenBodyIsUnreachable() throws Exception {
        HttpResponse<InputStream> response = mock(HttpResponse.class);
        given(response.statusCode()).willReturn(200);
        given(response.body()).willReturn(new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("connection reset");
            }
        });
        HttpClient client = mock(HttpClient.class);
        given(client.<InputStream>send(any(), any())).willReturn(response);
        CalendarFetcher fetcher = new CalendarFetcher(properties, policy, client);

        assertThatThrownBy(() -> fetcher.fetch(CALENDAR))
                .isInstanceOf(CalendarFetchException.class)
                .hasFieldOrPropertyWithValue("kind", CalendarFetchException.Kind.UNREACHABLE)
                .hasMessage("Could not reach 127.0.0.1")
                .hasRootCauseMessage("connection reset");
    }
}

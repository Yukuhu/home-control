package dev.andre.homecontrol.sources.sports.calendar;

import dev.andre.homecontrol.sources.sports.SportsProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CalendarFetcherTest {

    private FakeCalendarServer server;
    private CalendarFetcher fetcher;
    private SportsProperties.Calendar properties;

    @BeforeEach
    void start() throws IOException {
        server = new FakeCalendarServer();
        properties = new SportsProperties.Calendar(Duration.ofHours(6), 1, 2, 2 * 1024 * 1024, 3, false);
        fetcher = new CalendarFetcher(properties, new CalendarUrlPolicy(true));
    }

    @AfterEach
    void stop() {
        server.close();
    }

    @Test
    void fetchesACalendar() {
        server.respondFixture("/private/token-abc123/basic.ics", "bundesliga.ics");

        String text = fetcher.fetch(server.url("/private/token-abc123/basic.ics"));

        assertThat(text).startsWith("BEGIN:VCALENDAR");
        FakeCalendarServer.Recorded request = server.requests("/private/token-abc123/basic.ics").getFirst();
        assertThat(request.header("accept")).contains("text/calendar");
        assertThat(request.header("user-agent")).isEqualTo("HomeControl");
    }

    @Test
    void decodesTheDeclaredCharset() {
        byte[] body = "BEGIN:VCALENDAR\nSUMMARY:Köln\nEND:VCALENDAR\n".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        server.respondBytes("/latin1.ics", 200, "text/calendar; charset=ISO-8859-1", body);
        assertThat(fetcher.fetch(server.url("/latin1.ics"))).contains("Köln");

        server.respondBytes("/unknown.ics", 200, "text/calendar; charset=x-nope",
                "BEGIN:VCALENDAR\nEND:VCALENDAR\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(fetcher.fetch(server.url("/unknown.ics"))).startsWith("BEGIN:VCALENDAR");
    }

    @Test
    void followsValidatedRedirects() {
        server.redirect("/old", 301, "/new");
        server.redirect("/new", 302, server.url("/cal.ics").toString());
        server.respondFixture("/cal.ics", "bundesliga.ics");

        assertThat(fetcher.fetch(server.url("/old"))).startsWith("BEGIN:VCALENDAR");

        server.redirect("/r0", 302, "/r1");
        server.redirect("/r1", 302, "/r2");
        server.redirect("/r2", 302, "/r3");
        server.redirect("/r3", 302, "/r4");
        assertThatThrownBy(() -> fetcher.fetch(server.url("/r0")))
                .hasMessage("The calendar link redirected too many times");
    }

    @Test
    void mapsStatusesToUnauthorizedNotFoundAndBadResponse() {
        server.respond("/401", 401, "text/plain", "");
        assertThatThrownBy(() -> fetcher.fetch(server.url("/401")))
                .isInstanceOf(CalendarFetchException.class)
                .hasFieldOrPropertyWithValue("kind", CalendarFetchException.Kind.UNAUTHORIZED)
                .hasMessageContaining("refused access");

        server.respond("/404", 404, "text/plain", "");
        assertThatThrownBy(() -> fetcher.fetch(server.url("/404")))
                .hasFieldOrPropertyWithValue("kind", CalendarFetchException.Kind.NOT_FOUND);

        server.respond("/500", 500, "text/plain", "");
        assertThatThrownBy(() -> fetcher.fetch(server.url("/500")))
                .hasFieldOrPropertyWithValue("kind", CalendarFetchException.Kind.BAD_RESPONSE)
                .hasMessageContaining("answered HTTP 500");
    }

    @ParameterizedTest
    @CsvSource({"401,UNAUTHORIZED", "403,UNAUTHORIZED", "404,NOT_FOUND", "410,NOT_FOUND"})
    void mapsStatuses(int status, String kind) {
        server.respond("/s" + status, status, "text/plain", "");
        assertThatThrownBy(() -> fetcher.fetch(server.url("/s" + status)))
                .hasFieldOrPropertyWithValue("kind", CalendarFetchException.Kind.valueOf(kind));
    }

    @Test
    void capsTheBody() {
        byte[] tooLarge = new byte[properties.maxBytes() + 1];
        server.respondBytes("/too-large.ics", 200, "text/calendar", tooLarge);
        assertThatThrownBy(() -> fetcher.fetch(server.url("/too-large.ics")))
                .isInstanceOf(CalendarFetchException.class)
                .hasFieldOrPropertyWithValue("kind", CalendarFetchException.Kind.TOO_LARGE)
                .hasMessageContaining("2 MB");
    }

    @Test
    void timesOut() {
        server.delay(Duration.ofSeconds(3));
        server.respond("/slow.ics", 200, "text/calendar", "BEGIN:VCALENDAR\nEND:VCALENDAR\n");

        assertThatThrownBy(() -> fetcher.fetch(server.url("/slow.ics")))
                .isInstanceOf(CalendarFetchException.class)
                .hasFieldOrPropertyWithValue("kind", CalendarFetchException.Kind.UNREACHABLE)
                .hasMessageContaining("Could not reach 127.0.0.1");
    }

    @Test
    void neverLeaksThePath() {
        server.respond("/private/token-abc123/old", 404, "text/plain", "");
        try {
            fetcher.fetch(server.url("/private/token-abc123/old"));
        } catch (CalendarFetchException e) {
            assertThat(e.getMessage()).doesNotContain("token-abc123", "/private", "?");
            assertThat(e.toString()).doesNotContain("token-abc123", "/private", "?");
        }
    }

    @Test
    void refusesRedirectsToBlockedAddresses() {
        CalendarUrlPolicy.HostResolver resolver = host -> switch (host) {
            case "127.0.0.1" -> new InetAddress[] {InetAddress.getByName("127.0.0.1")};
            case "metadata.test" -> new InetAddress[] {InetAddress.getByName("169.254.169.254")};
            default -> throw new UnknownHostException(host);
        };
        CalendarFetcher blockedFetcher = new CalendarFetcher(properties, new CalendarUrlPolicy(true, resolver));
        server.redirect("/hop", 302, "http://metadata.test/latest");

        assertThatThrownBy(() -> blockedFetcher.fetch(server.url("/hop")))
                .isInstanceOf(CalendarFetchException.class)
                .hasFieldOrPropertyWithValue("kind", CalendarFetchException.Kind.BLOCKED)
                .hasMessageContaining("metadata.test");

        server.redirect("/ftp", 302, "ftp://x/y");
        assertThatThrownBy(() -> blockedFetcher.fetch(server.url("/ftp")))
                .isInstanceOf(CalendarFetchException.class)
                .hasFieldOrPropertyWithValue("kind", CalendarFetchException.Kind.BAD_RESPONSE)
                .hasMessage("127.0.0.1 redirected to a link Home Control does not follow");
    }
}

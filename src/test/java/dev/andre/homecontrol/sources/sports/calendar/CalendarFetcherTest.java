package dev.andre.homecontrol.sources.sports.calendar;

import dev.andre.homecontrol.core.content.ContentSourceException;
import dev.andre.homecontrol.sources.http.OutboundAddressPolicy;
import dev.andre.homecontrol.sources.sports.settings.SportsProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CalendarFetcherTest {

    private FakeCalendarServer server;
    private CalendarFetcher fetcher;
    private SportsProperties.Calendar properties;

    @BeforeEach
    void start() throws IOException {
        server = new FakeCalendarServer();
        properties = new SportsProperties.Calendar(Duration.ofHours(6), Duration.ofSeconds(1), Duration.ofSeconds(2),
                2 * 1024 * 1024, 3, false);
        fetcher = new CalendarFetcher(properties, new OutboundAddressPolicy(true));
    }

    @AfterEach
    void stop() {
        fetcher.close();
        server.close();
    }

    /** A host name no DNS knows: the fetch can only reach the fake through the address the policy vetted. */
    private URI unresolvable(String path) {
        return URI.create("http://calendar.test:" + server.url("/").getPort() + path);
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
                .hasMessage("The calendar at 127.0.0.1 sent a response Home Control cannot read (too many redirects)");
    }

    @Test
    void mapsStatusesToUnauthorizedNotFoundAndServerError() {
        server.respond("/401", 401, "text/plain", "");
        var unauthorizedUrl = server.url("/401");
        assertThatThrownBy(() -> fetcher.fetch(unauthorizedUrl))
                .isInstanceOf(CalendarFetchException.class)
                .hasFieldOrPropertyWithValue("kind", ContentSourceException.Kind.UNAUTHORIZED)
                .hasMessageContaining("refused access");

        server.respond("/404", 404, "text/plain", "");
        assertThatThrownBy(() -> fetcher.fetch(server.url("/404")))
                .hasFieldOrPropertyWithValue("kind", ContentSourceException.Kind.NOT_FOUND);

        server.respond("/500", 500, "text/plain", "");
        assertThatThrownBy(() -> fetcher.fetch(server.url("/500")))
                .hasFieldOrPropertyWithValue("kind", ContentSourceException.Kind.SERVER_ERROR)
                .hasMessageContaining("answered HTTP 500");
    }

    @ParameterizedTest
    @CsvSource({"401,UNAUTHORIZED", "403,UNAUTHORIZED", "404,NOT_FOUND", "410,NOT_FOUND"})
    void mapsStatuses(int status, String kind) {
        server.respond("/s" + status, status, "text/plain", "");
        assertThatThrownBy(() -> fetcher.fetch(server.url("/s" + status)))
                .hasFieldOrPropertyWithValue("kind", ContentSourceException.Kind.valueOf(kind));
    }

    @Test
    void capsTheBody() {
        byte[] tooLarge = new byte[properties.maxBytes() + 1];
        server.respondBytes("/too-large.ics", 200, "text/calendar", tooLarge);
        var tooLargeUrl = server.url("/too-large.ics");
        assertThatThrownBy(() -> fetcher.fetch(tooLargeUrl))
                .isInstanceOf(CalendarFetchException.class)
                .hasFieldOrPropertyWithValue("kind", ContentSourceException.Kind.TOO_LARGE)
                .hasMessageContaining("2 MB");
    }

    @Test
    void timesOut() {
        server.delay(Duration.ofSeconds(3));
        server.respond("/slow.ics", 200, "text/calendar", "BEGIN:VCALENDAR\nEND:VCALENDAR\n");

        var slowUrl = server.url("/slow.ics");
        assertThatThrownBy(() -> fetcher.fetch(slowUrl))
                .isInstanceOf(CalendarFetchException.class)
                .hasFieldOrPropertyWithValue("kind", ContentSourceException.Kind.UNREACHABLE)
                .hasMessage("Could not reach the calendar at 127.0.0.1 (request timed out)");
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
        OutboundAddressPolicy.Resolver resolver = host -> switch (host) {
            case "127.0.0.1" -> new InetAddress[] {InetAddress.getByName("127.0.0.1")};
            case "metadata.test" -> new InetAddress[] {InetAddress.getByName("169.254.169.254")};
            default -> throw new UnknownHostException(host);
        };
        CalendarFetcher blockedFetcher = new CalendarFetcher(properties, new OutboundAddressPolicy(true, resolver));
        server.redirect("/hop", 302, "http://metadata.test/latest");

        var metadataRedirectUrl = server.url("/hop");
        assertThatThrownBy(() -> blockedFetcher.fetch(metadataRedirectUrl))
                .isInstanceOf(CalendarFetchException.class)
                .hasFieldOrPropertyWithValue("kind", ContentSourceException.Kind.BLOCKED)
                .hasMessageContaining("metadata.test");

        server.redirect("/ftp", 302, "ftp://x/y");
        var ftpRedirectUrl = server.url("/ftp");
        assertThatThrownBy(() -> blockedFetcher.fetch(ftpRedirectUrl))
                .isInstanceOf(CalendarFetchException.class)
                .hasFieldOrPropertyWithValue("kind", ContentSourceException.Kind.BAD_RESPONSE)
                .hasMessage("The calendar at 127.0.0.1 redirects to a link Home Control does not follow");
    }

    @Test
    void aCalendarOnThisMachineIsRefusedWithTheSettingThatAllowsIt() throws Exception {
        try (CalendarFetcher local = new CalendarFetcher(properties, new OutboundAddressPolicy(false))) {
            var onThisMachine = server.url("/private/token-abc123/bl.ics");

            assertThatThrownBy(() -> local.fetch(onThisMachine))
                    .isInstanceOf(CalendarFetchException.class)
                    .hasFieldOrPropertyWithValue("kind", ContentSourceException.Kind.BLOCKED)
                    .hasMessage("Home Control does not connect to 127.0.0.1 (address not allowed): that address belongs to"
                            + " this machine or its network link. If the calendar is served on this machine, set"
                            + " HOME_CONTROL_SPORTS_CALENDAR_ALLOW_LOOPBACK=true.");
        }
        assertThat(server.count("/private/token-abc123/bl.ics")).isZero();
    }

    @Test
    void connectsToTheAddressThePolicyVetted() {
        server.respondFixture("/cal.ics", "bundesliga.ics");
        OutboundAddressPolicy.Resolver loopback = host -> new InetAddress[] {InetAddress.ofLiteral("127.0.0.1")};

        try (CalendarFetcher pinned = new CalendarFetcher(properties, new OutboundAddressPolicy(true, loopback))) {
            assertThat(pinned.fetch(unresolvable("/cal.ics"))).startsWith("BEGIN:VCALENDAR");
        }
    }

    @Test
    void aHostThatWouldRebindToThisMachineIsLookedUpOnceAndNeverReachesIt() {
        server.respondFixture("/cal.ics", "bundesliga.ics");
        AtomicInteger lookups = new AtomicInteger();
        // The first answer passes the policy; every later one would point at this machine, which the policy refuses.
        // The connection goes to the first answer (a documentation address nothing answers on), never to a second.
        OutboundAddressPolicy.Resolver rebinding = host -> new InetAddress[] {
                InetAddress.ofLiteral(lookups.getAndIncrement() == 0 ? "192.0.2.10" : "127.0.0.1")};

        try (CalendarFetcher rebound = new CalendarFetcher(properties, new OutboundAddressPolicy(false, rebinding))) {
            URI url = unresolvable("/cal.ics");
            assertThatThrownBy(() -> rebound.fetch(url))
                    .isInstanceOf(CalendarFetchException.class)
                    .hasFieldOrPropertyWithValue("kind", ContentSourceException.Kind.UNREACHABLE);
        }
        assertThat(lookups).hasValue(1);
        assertThat(server.count("/cal.ics")).isZero();
    }

    @Test
    void aRedirectChainSharesOneDeadline() {
        var oneSecond = new SportsProperties.Calendar(Duration.ofHours(6), Duration.ofSeconds(1), Duration.ofSeconds(1),
                2 * 1024 * 1024, 3, false);
        server.redirect("/r1.ics", 302, "/r2.ics");
        server.redirect("/r2.ics", 302, "/r3.ics");
        server.respondFixture("/r3.ics", "bundesliga.ics");
        server.delay(Duration.ofMillis(400));
        try (CalendarFetcher timed = new CalendarFetcher(oneSecond, new OutboundAddressPolicy(true))) {
            URI first = server.url("/r1.ics");

            assertThatThrownBy(() -> timed.fetch(first))
                    .isInstanceOf(CalendarFetchException.class)
                    .hasFieldOrPropertyWithValue("kind", ContentSourceException.Kind.UNREACHABLE)
                    .hasMessageEndingWith("(request timed out)");
        }
    }

    @Test
    void refusesAnIpv4MappedLoopbackAddress() throws UnknownHostException {
        server.respondFixture("/cal.ics", "bundesliga.ics");
        byte[] mappedLoopback = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, (byte) 0xff, (byte) 0xff, 127, 0, 0, 1};
        InetAddress mapped = Inet6Address.getByAddress(null, mappedLoopback, (NetworkInterface) null);
        try (CalendarFetcher refusing = new CalendarFetcher(properties, new OutboundAddressPolicy(false, host -> new InetAddress[] {mapped}))) {
            URI url = unresolvable("/cal.ics");

            assertThatThrownBy(() -> refusing.fetch(url))
                    .isInstanceOf(CalendarFetchException.class)
                    .hasFieldOrPropertyWithValue("kind", ContentSourceException.Kind.BLOCKED);
        }
        assertThat(server.count("/cal.ics")).isZero();
    }
    @Test
    void aStatusSentenceNamesTheHostThatAnswered() {
        server.redirect("/moved.ics", 302, unresolvable("/gone.ics").toString());
        server.respond("/gone.ics", 404, "text/plain", "");
        OutboundAddressPolicy.Resolver loopback = host -> new InetAddress[] {InetAddress.ofLiteral("127.0.0.1")};
        try (CalendarFetcher redirected = new CalendarFetcher(properties, new OutboundAddressPolicy(true, loopback))) {
            URI moved = server.url("/moved.ics");

            assertThatThrownBy(() -> redirected.fetch(moved))
                    .isInstanceOf(CalendarFetchException.class)
                    .hasMessage("calendar.test has no calendar at that link");
        }
    }
}

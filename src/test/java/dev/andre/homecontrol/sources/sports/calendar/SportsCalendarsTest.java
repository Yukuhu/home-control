package dev.andre.homecontrol.sources.sports.calendar;

import dev.andre.homecontrol.security.LoginService;
import dev.andre.homecontrol.security.PasswordRejectedException;
import dev.andre.homecontrol.sources.sports.JsonFileSportsStore;
import dev.andre.homecontrol.sources.sports.SportsProperties;
import dev.andre.homecontrol.sources.sports.SportsSettings;
import dev.andre.homecontrol.sources.sports.SportsSettingsService;
import dev.andre.homecontrol.sources.sports.SportsTimeZones;
import dev.andre.homecontrol.storage.SecretStore;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class SportsCalendarsTest {

    @TempDir
    Path dir;

    private FakeCalendarServer server;
    private SecretStore secrets;
    private LoginService login;
    private SportsSettingsService settingsService;
    private SportsProperties properties;
    private CalendarUrlPolicy policy;
    private CalendarFetcher fetcher;
    private CalendarSchedule schedule;
    private SportsCalendars calendars;
    private HttpServletRequest http;
    private SecureRandom random;

    private static SecureRandom incrementingRandom() {
        java.util.concurrent.atomic.AtomicInteger counter = new java.util.concurrent.atomic.AtomicInteger();
        return new SecureRandom() {
            @Override
            public void nextBytes(byte[] bytes) {
                Arrays.fill(bytes, (byte) 0);
                bytes[bytes.length - 1] = (byte) counter.incrementAndGet();
            }
        };
    }

    @BeforeEach
    void setUp() throws IOException {
        server = new FakeCalendarServer();
        server.respondFixture("/private/token-abc123/bl.ics", "bundesliga.ics");

        secrets = mock(SecretStore.class);
        login = mock(LoginService.class);
        given(login.loginRequired()).willReturn(false);
        http = mock(HttpServletRequest.class);
        random = incrementingRandom();

        JsonFileSportsStore store = new JsonFileSportsStore(dir.resolve("sports.json"));
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        settingsService = new SportsSettingsService(store, events);

        properties = new SportsProperties(true, "", 30, 10, 10, Duration.ofMinutes(120),
                new SportsProperties.Calendar(Duration.ofHours(6), 1, 2, 5242880, 3, true),
                new SportsProperties.TheSportsDb(true, URI.create("http://127.0.0.1:9/api/v1/json"), "123",
                        Duration.ofHours(24), 1, 2, null));

        policy = new CalendarUrlPolicy(true);
        fetcher = new CalendarFetcher(properties.calendar(), policy);
        SportsTimeZones zones = mock(SportsTimeZones.class);
        given(zones.effective()).willReturn(ZoneId.of("Europe/Berlin"));
        schedule = new CalendarSchedule(settingsService, fetcher, secrets, properties, zones,
                Clock.fixed(Instant.parse("2026-09-19T14:00:00Z"), ZoneOffset.UTC));

        calendars = new SportsCalendars(settingsService, policy, fetcher, schedule, secrets, login, properties,
                Clock.fixed(Instant.parse("2026-09-16T10:00:00Z"), ZoneOffset.UTC), random);
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void addsACalendarAsASecret() {
        String url = server.url("/private/token-abc123/bl.ics").toString();
        doAnswer(invocation -> {
            assertThat(server.count("/private/token-abc123/bl.ics")).isZero();
            return null;
        }).when(login).checkNewPassword("household password", "household password");

        SportsSettings.CalendarEntry entry = calendars.add(
                new SportsCalendars.AddCalendar(url, "", "household password", "household password"), http);

        verify(login).checkNewPassword("household password", "household password");
        verify(login).storeSecrets(java.util.Map.of("sports.calendar." + entry.id(), url),
                "household password", "household password", http);
        assertThat(entry.id()).matches("c-[0-9a-f]{12}");
        assertThat(entry.label()).isEqualTo("Bundesliga 2026/27");
        assertThat(entry.host()).isEqualTo("127.0.0.1");
        assertThat(entry.provider()).isNull();

        assertThat(server.count("/private/token-abc123/bl.ics")).isEqualTo(1);
    }

    @Test
    void webcalLinksAreFetchedOverHttps() {
        int port = server.url("/x.ics").getPort();
        URI parsed = policy.parse("webcal://127.0.0.1:" + port + "/x.ics");
        assertThat(parsed.getScheme()).isEqualTo("https");
    }

    @Test
    void labelsFallBackToTheHost() {
        server.respond("/no-name.ics", 200, "text/calendar",
                IcsFixtureHelper.fixture("recurring.ics").replace("X-WR-CALNAME:Weekly sport\n", ""));
        String url = server.url("/no-name.ics").toString();

        SportsSettings.CalendarEntry entry = calendars.add(
                new SportsCalendars.AddCalendar(url, "", "household password", "household password"), http);
        assertThat(entry.label()).isEqualTo("127.0.0.1");

        String url2 = server.url("/private/token-abc123/bl.ics").toString();
        SportsSettings.CalendarEntry entry2 = calendars.add(
                new SportsCalendars.AddCalendar(url2, " My games ", "household password", "household password"), http);
        assertThat(entry2.label()).isEqualTo("My games");
    }

    @Test
    void rejectsBadInput() {
        var preparedArg151_0 = new SportsCalendars.AddCalendar("ftp://x/y", "", "household password", "household password");
        assertThatThrownBy(() -> calendars.add(preparedArg151_0, http))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Use an http, https or webcal link");

        assertThatThrownBy(() -> calendars.add(new SportsCalendars.AddCalendar(
                server.url("/private/token-abc123/bl.ics").toString(), "x".repeat(81), "household password", "household password"), http))
                .hasMessage("Keep the name under 80 characters");

        server.respond("/page.html", 200, "text/html", "<html></html>");
        assertThatThrownBy(() -> calendars.add(new SportsCalendars.AddCalendar(
                server.url("/page.html").toString(), "", "household password", "household password"), http))
                .hasMessage("That link did not return a calendar (.ics)");

        server.respond("/missing.ics", 404, "text/plain", "");
        var preparedArg165_0 = new SportsCalendars.AddCalendar(
                server.url("/missing.ics").toString(), "", "household password", "household password");
        assertThatThrownBy(() -> calendars.add(preparedArg165_0, http))
                .isInstanceOf(CalendarFetchException.class).hasMessage("127.0.0.1 has no calendar at that link");

        doThrow(new PasswordRejectedException("no")).when(login).checkNewPassword(any(), any());
        var preparedArg170_0 = new SportsCalendars.AddCalendar(
                server.url("/private/token-abc123/bl.ics").toString(), "", "x", "y");
        assertThatThrownBy(() -> calendars.add(preparedArg170_0, http))
                .isInstanceOf(PasswordRejectedException.class);
        assertThat(server.count("/private/token-abc123/bl.ics")).isZero();
    }

    @Test
    void refusesDuplicatesAndTooManyCalendars() {
        String url = server.url("/private/token-abc123/bl.ics").toString();
        given(secrets.secret("sports.calendar.c-000000000000")).willReturn(java.util.Optional.of(url));
        settingsService.update(s -> s.withCalendars(List.of(
                new SportsSettings.CalendarEntry("c-000000000000", "Existing", "127.0.0.1", null, Instant.EPOCH))));

        assertThatThrownBy(() -> calendars.add(new SportsCalendars.AddCalendar(url, "", "household password", "household password"), http))
                .hasMessage("That calendar is already added");

        SportsProperties limited = new SportsProperties(true, "", 30, 1, 10, Duration.ofMinutes(120),
                properties.calendar(), properties.theSportsDb());
        SportsCalendars limitedCalendars = new SportsCalendars(settingsService, policy, fetcher, schedule, secrets, login,
                limited, Clock.fixed(Instant.parse("2026-09-16T10:00:00Z"), ZoneOffset.UTC), random);
        assertThatThrownBy(() -> limitedCalendars.add(
                new SportsCalendars.AddCalendar(server.url("/other.ics").toString(), "", "household password", "household password"), http))
                .hasMessage("You can add up to 1 calendars");
    }

    @Test
    void laterCalendarsNeedNoNewPassword() {
        given(login.loginRequired()).willReturn(true);
        String url = server.url("/private/token-abc123/bl.ics").toString();

        calendars.add(new SportsCalendars.AddCalendar(url, "", null, null), http);

        verify(login, times(0)).checkNewPassword(any(), any());
        verify(login).storeSecrets(any(), org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.eq(http));
    }

    @Test
    void removesCalendarAndSecret() {
        String url = server.url("/private/token-abc123/bl.ics").toString();
        SportsSettings.CalendarEntry entry = calendars.add(
                new SportsCalendars.AddCalendar(url, "", "household password", "household password"), http);

        SportsSettings.CalendarEntry removed = calendars.remove(entry.id());

        assertThat(removed.id()).isEqualTo(entry.id());
        assertThat(settingsService.current().calendar(entry.id())).isEmpty();
        verify(login).removeSecrets(List.of("sports.calendar." + entry.id()));

        assertThatThrownBy(() -> calendars.remove("c-000000000000"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("No calendar c-000000000000");
    }

    @Test
    void requestToStringIsRedacted() {
        SportsCalendars.AddCalendar request = new SportsCalendars.AddCalendar(
                "https://example.org/private/secret.ics", "My calendar", "hunter2000000", "hunter2000000");
        assertThat(request.toString()).doesNotContain("secret.ics", "hunter2000000").contains("My calendar");
    }

    private static final class IcsFixtureHelper {
        static String fixture(String name) {
            try (var in = IcsFixtureHelper.class.getResourceAsStream("/fixtures/ics/" + name)) {
                return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }
    }
}

package dev.andre.homecontrol.web;

import dev.andre.homecontrol.adapters.androidtv.AndroidTvSettings;
import dev.andre.homecontrol.adapters.androidtv.protocol.CertificateStore;
import dev.andre.homecontrol.adapters.androidtv.protocol.FakeRemoteServer;
import dev.andre.homecontrol.device.DeviceManager;
import dev.andre.homecontrol.sources.sports.calendar.FakeCalendarServer;
import dev.andre.homecontrol.sources.sports.thesportsdb.FakeTheSportsDbServer;
import dev.andre.homecontrol.storage.SecretStore;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Sports through the real application over real sockets: a fake calendar server and a fake
 * TheSportsDB ↔ the sports source ↔ the planner ↔ a fake Android TV Shield ↔ HTTP, with the login
 * gate in front. Mirrors the shape of {@link JellyfinEndToEndTest} (same client/helper pattern).
 *
 * <p>The brief for this test assumed a JSON rails endpoint ({@code GET /sources/{s}/rails/{r}}
 * returning {@code "status":"READY"} and item objects); the real endpoint is
 * {@code GET /rails/{sourceId}/{railId}} (see {@link RailController}), which renders the same HTML
 * tile fragment the dashboard uses. This test reads that fragment's {@code data-*} tile attributes
 * instead of JSON fields — the same information, in the shape the real server actually returns.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SportsEndToEndTest {

    static final String LOGIN = "household password";
    static final String TOKEN = "token-e2e-7f3a91";
    static final String EVENT_LINK = "https://www.dazn.com/de-DE/fixture/ContentId:e2e1a2b3c4d5e6f7g8h9i0";
    static final FakeCalendarServer CALENDARS;
    static final FakeTheSportsDbServer SPORTSDB;
    static Path dataDir;

    /** Shared with {@link #aPersonalKeyIsASecretAndGoesBackToFree()}: that test reuses this login. */
    static HttpClient sharedBrowser;
    static String calendarId;

    static {
        try {
            CALENDARS = new FakeCalendarServer();
            SPORTSDB = new FakeTheSportsDbServer().withStandardResponses();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void isolated(DynamicPropertyRegistry registry) throws IOException {
        dataDir = Files.createTempDirectory("sports-e2e");
        registry.add("shield.data-dir", dataDir::toString);
        registry.add("home-control.sports.calendar.allow-loopback", () -> "true");
        registry.add("home-control.sports.thesportsdb.api-base-url", () -> SPORTSDB.apiBase().toString());
    }

    static void stopServers() {
        CALENDARS.close();
        SPORTSDB.close();
    }

    @LocalServerPort
    int port;

    @Autowired
    DeviceManager devices;

    @Autowired
    CertificateStore certificates;

    @Autowired
    SecretStore secrets;

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final List<String> browserBodies = new ArrayList<>();

    private HttpClient browser() {
        if (sharedBrowser == null) {
            sharedBrowser = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
        }
        return sharedBrowser;
    }

    private final HttpClient stranger = HttpClient.newHttpClient();

    private HttpResponse<String> send(HttpClient client, HttpRequest.Builder request) throws Exception {
        HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        if (client == browser()) {
            browserBodies.add(response.body());
        }
        return response;
    }

    private HttpRequest.Builder get(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).header("Accept", "application/json");
    }

    private HttpRequest.Builder page(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).header("Accept", "text/html");
    }

    private HttpRequest.Builder post(String path, Map<String, String> form) {
        String body = form.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
    }

    private static String utcBasic(Instant instant) {
        return DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(instant);
    }

    /** The rail tile fragment's {@code data-*} attributes, one map per tile, in document order. */
    private static List<Map<String, String>> tiles(String html) {
        List<Map<String, String>> tiles = new ArrayList<>();
        Matcher tileTag = Pattern.compile("<button[^>]*class=\"tile\"[^>]*>").matcher(html);
        Pattern attr = Pattern.compile("(data-[a-z-]+)=\"([^\"]*)\"");
        while (tileTag.find()) {
            Map<String, String> attrs = new LinkedHashMap<>();
            Matcher a = attr.matcher(tileTag.group());
            while (a.find()) {
                attrs.put(a.group(1), a.group(2));
            }
            tiles.add(attrs);
        }
        return tiles;
    }

    @Test
    @Order(1)
    void mappedEventsOpenDaznAndPastedLinksOpenTheEvent() throws Exception {
        HttpClient browser = browser();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MINUTES);
        String calendarText = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nX-WR-CALNAME:E2E league\r\n"
                + "BEGIN:VEVENT\r\nUID:e2e-live@fixtures.example\r\nDTSTART:" + utcBasic(now.minus(Duration.ofMinutes(30)))
                + "\r\nDTEND:" + utcBasic(now.plus(Duration.ofMinutes(90))) + "\r\nSUMMARY:Calendar Live Match\r\nEND:VEVENT\r\n"
                + "BEGIN:VEVENT\r\nUID:e2e-over@fixtures.example\r\nDTSTART:" + utcBasic(now.minus(Duration.ofHours(5)))
                + "\r\nDTEND:" + utcBasic(now.minus(Duration.ofHours(3))) + "\r\nSUMMARY:Calendar Finished Match\r\nEND:VEVENT\r\n"
                + "END:VCALENDAR\r\n";
        CALENDARS.respond("/private/" + TOKEN + "/league.ics", 200, "text/calendar", calendarText);

        Instant tsdbTime = now.minus(Duration.ofMinutes(20));
        LocalDate tsdbDate = LocalDate.ofInstant(tsdbTime, ZoneOffset.UTC);
        String tsdbTimestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC).format(tsdbTime);
        String tsdbTimeOnly = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneOffset.UTC).format(tsdbTime);
        String eventsJson = "{\"events\":[{\"idEvent\":\"9000001\",\"idLeague\":\"4331\",\"strLeague\":\"German Bundesliga\","
                + "\"strSport\":\"Soccer\",\"strEvent\":\"TheSportsDB Live Match\",\"strHomeTeam\":\"Home\",\"strAwayTeam\":\"Away\","
                + "\"strTimestamp\":\"" + tsdbTimestamp + "\",\"dateEvent\":\"" + tsdbDate + "\",\"strTime\":\"" + tsdbTimeOnly + "\","
                + "\"strThumb\":null,\"strPoster\":null,\"strStatus\":\"NS\",\"strPostponed\":\"no\"}]}";
        SPORTSDB.respondJson("eventsday.php", Map.of("d", tsdbDate.toString(), "l", "4331"), 200, eventsJson);

        try (FakeRemoteServer shieldRemote = new FakeRemoteServer()) {
            certificates.loadOrCreate("shield-e2e");
            devices.adopt(AndroidTvSettings.device("shield-e2e", "Shield", "127.0.0.1", shieldRemote.port(), null, Instant.now()));
            await().until(() -> devices.state("shield-e2e").connected());
            try {
                // Step 2: before any secret, /setup is open to a stranger.
                assertThat(send(stranger, page("/setup")).statusCode()).isEqualTo(200);

                // Step 3: the free TheSportsDB key needs no login.
                HttpResponse<String> addCompetition = send(browser, post("/setup/sources/sports/competitions", Map.of("leagueId", "4331")));
                assertThat(addCompetition.statusCode()).isEqualTo(302);
                assertThat(send(stranger, page("/setup")).statusCode()).isEqualTo(200);
                assertThat(send(stranger, page("/setup")).body()).contains("German Bundesliga");
                assertThat(SPORTSDB.last("lookupleague.php").key()).isEqualTo("123");

                // Step 4: the calendar link is stored as a secret, so it sets the login password.
                HttpResponse<String> addCalendar = send(browser, post("/setup/sources/sports/calendars", Map.of(
                        "url", CALENDARS.url("/private/" + TOKEN + "/league.ics").toString(), "label", "",
                        "loginPassword", LOGIN, "loginPasswordConfirmation", LOGIN)));
                assertThat(addCalendar.statusCode()).isEqualTo(302);
                String setupAfterCalendar = send(browser, page("/setup")).body();
                assertThat(setupAfterCalendar).contains("E2E league").contains("127.0.0.1").doesNotContain(TOKEN);
                assertThat(send(stranger, get("/sources")).statusCode()).isEqualTo(401);

                // Step 5.
                assertThat(send(browser, post("/setup/sources/preferences/locale",
                        Map.of("locale", "de-DE", "region", "DE", "providers", "dazn"))).statusCode()).isEqualTo(302);
                assertThat(send(browser, post("/setup/sources/sports/time-zone", Map.of("timeZone", "Europe/Berlin"))).statusCode())
                        .isEqualTo(302);

                // Step 6: read the generated calendar id, and confirm the secret token never reaches disk in the clear.
                JsonNode sportsJson = mapper.readTree(Files.readString(dataDir.resolve("sports.json")));
                calendarId = sportsJson.path("calendars").get(0).path("id").asString();
                assertThat(Files.readString(dataDir.resolve("sports.json"))).doesNotContain(TOKEN).doesNotContain("/private/");

                assertThat(send(browser, post("/setup/sources/sports/providers", Map.of(
                        "provider:calendar:" + calendarId, "dazn", "provider:thesportsdb:4331", "dazn"))).statusCode())
                        .isEqualTo(302);
                assertThat(send(browser, page("/setup")).body()).contains("Where you watch it (your setting)");

                // Step 7: refresh the rail and wait for it to be ready with both live matches mapped
                // to DAZN. RailCache.start() is a no-op while a fetch is already in flight, and a
                // READY rail keeps serving its last items while a refetch is pending, so a refresh
                // fired right after Step 6's provider save can be swallowed or can land before that
                // mapping is visible in the fetched items. Keep refreshing until the tiles actually
                // show it (mirrors the Step 12/13 awaits below).
                HttpResponse<String>[] holder = new HttpResponse[1];
                await().atMost(Duration.ofSeconds(10)).until(() -> {
                    send(browser, post("/rails/sports/live-today/refresh", Map.of()));
                    holder[0] = send(browser, get("/rails/sports/live-today"));
                    return holder[0].statusCode() == 200
                            && holder[0].body().contains("data-subtitle=\"Live · E2E league · DAZN (your setting)\"")
                            && holder[0].body().contains("data-subtitle=\"Live · German Bundesliga · DAZN (your setting)\"");
                });
                String railBody = holder[0].body();
                List<Map<String, String>> items = tiles(railBody);
                assertThat(items).hasSizeGreaterThanOrEqualTo(2);
                Map<String, String> calendarTile = items.stream()
                        .filter(t -> "Calendar Live Match".equals(t.get("data-title"))).findFirst().orElseThrow();
                assertThat(calendarTile).containsEntry("data-subtitle", "Live · E2E league · DAZN (your setting)");
                assertThat(calendarTile).containsEntry("data-kind", "LIVE_EVENT");
                assertThat(calendarTile.get("data-starts-at")).isNotBlank();
                assertThat(calendarTile.get("data-ends-at")).isNotBlank();
                Map<String, String> tsdbTile = items.stream()
                        .filter(t -> "TheSportsDB Live Match".equals(t.get("data-title"))).findFirst().orElseThrow();
                assertThat(tsdbTile).containsEntry("data-subtitle", "Live · German Bundesliga · DAZN (your setting)");
                assertThat(tsdbTile).containsEntry("data-item", "tsdb:9000001");
                assertThat(railBody).doesNotContain("Calendar Finished Match").doesNotContain(TOKEN)
                        .doesNotContain("dazn.com").doesNotContain("playables").doesNotContain("AppLink");

                // Step 8.
                HttpResponse<String> preview = send(browser, get("/devices/shield-e2e/route-preview?source=sports&item=tsdb:9000001"));
                JsonNode previewJson = mapper.readTree(preview.body());
                assertThat(previewJson.path("route").path("description").asString()).isEqualTo("Open the DAZN app (not this event)");
                assertThat(previewJson.path("pin").path("upgradeOf").asString()).isEqualTo("sports/tsdb:9000001");
                assertThat(previewJson.path("pin").path("serviceName").asString()).isEqualTo("DAZN");

                // Step 9.
                HttpResponse<String> attempt = send(browser, post("/devices/shield-e2e/play-attempt",
                        Map.of("source", "sports", "item", "tsdb:9000001")));
                assertThat(attempt.statusCode()).isEqualTo(200);
                assertThat(mapper.readTree(attempt.body()).path("played").asBoolean()).isTrue();
                assertThat(shieldRemote.nextAppLink()).isEqualTo("https://www.dazn.com/");

                // Step 10: paste an event link.
                HttpResponse<String> upgrade = send(browser, post("/setup/sources/pinned/upgrade",
                        Map.of("url", EVENT_LINK, "upgradeOf", "sports/tsdb:9000001")));
                assertThat(upgrade.statusCode()).isEqualTo(200);
                assertThat(mapper.readTree(upgrade.body()).path("title").asString()).isEqualTo("TheSportsDB Live Match");

                // Step 11: the pasted link now wins.
                HttpResponse<String> previewAfterPin = send(browser, get("/devices/shield-e2e/route-preview?source=sports&item=tsdb:9000001"));
                JsonNode previewAfterPinJson = mapper.readTree(previewAfterPin.body());
                assertThat(previewAfterPinJson.path("route").path("description").asString()).isEqualTo("Open in the DAZN app");
                assertThat(previewAfterPinJson.path("pin").isNull() || previewAfterPinJson.path("pin").isMissingNode()).isTrue();
                send(browser, post("/devices/shield-e2e/play-attempt", Map.of("source", "sports", "item", "tsdb:9000001")));
                assertThat(shieldRemote.nextAppLink()).isEqualTo(EVENT_LINK);

                // Step 12: clearing the calendar's mapping leaves it with nothing playable.
                assertThat(send(browser, post("/setup/sources/sports/providers",
                        Map.of("provider:calendar:" + calendarId, ""))).statusCode()).isEqualTo(302);
                send(browser, post("/rails/sports/live-today/refresh", Map.of()));
                await().atMost(Duration.ofSeconds(10)).until(() -> {
                    holder[0] = send(browser, get("/rails/sports/live-today"));
                    return holder[0].statusCode() == 200 && holder[0].body().contains("Live · E2E league</span>");
                });
                Map<String, String> unmappedCalendarTile = tiles(holder[0].body()).stream()
                        .filter(t -> "Calendar Live Match".equals(t.get("data-title"))).findFirst().orElseThrow();
                String calendarItemId = unmappedCalendarTile.get("data-item");
                HttpResponse<String> unmappedPreview = send(browser,
                        get("/devices/shield-e2e/route-preview?source=sports&item=" + calendarItemId));
                JsonNode unmappedPreviewJson = mapper.readTree(unmappedPreview.body());
                assertThat(unmappedPreviewJson.path("playable").asBoolean()).isFalse();
                JsonNode unmappedPin = unmappedPreviewJson.path("pin");
                assertThat(unmappedPin.isMissingNode() || unmappedPin.isNull()
                        || unmappedPin.path("service").isNull() || unmappedPin.path("service").isMissingNode()).isTrue();
                HttpResponse<String> unplayableAttempt = send(browser, post("/devices/shield-e2e/play-attempt",
                        Map.of("source", "sports", "item", calendarItemId)));
                assertThat(unplayableAttempt.statusCode()).isEqualTo(422);
                assertThat(unplayableAttempt.body()).contains("This item has nothing playable");
                assertThat(shieldRemote.nextAppLink()).isNull();

                // Step 13: remove the calendar entirely.
                assertThat(send(browser, post("/setup/sources/sports/calendars/" + calendarId + "/remove", Map.of())).statusCode())
                        .isEqualTo(302);
                assertThat(secrets.names()).doesNotContain("sports.calendar." + calendarId);
                await().atMost(Duration.ofSeconds(10)).until(() -> {
                    holder[0] = send(browser, get("/rails/sports/live-today"));
                    return holder[0].statusCode() == 200 && !holder[0].body().contains("Calendar Live Match")
                            && holder[0].body().contains("TheSportsDB Live Match");
                });

                // Step 14.
                assertThat(Files.readString(dataDir.resolve("secrets.json"))).doesNotContain(TOKEN);
                assertThat(SPORTSDB.requests("eventsday.php")).allMatch(r -> "123".equals(r.key()));
                assertThat(CALENDARS.requests("/private/" + TOKEN + "/league.ics")).isNotEmpty();

                // Step 15.
                assertThat(browserBodies).noneMatch(body -> body.contains(TOKEN) || body.contains("/private/")
                        || body.contains("api/v1/json") || body.contains(FakeTheSportsDbServer.PERSONAL_KEY));
            } finally {
                devices.forget("shield-e2e");
            }
        }
    }

    @Test
    @Order(2)
    void aPersonalKeyIsASecretAndGoesBackToFree() throws Exception {
        HttpClient browser = browser();

        // Step 13 removed the only secret (the calendar), which also removes the login (spec §9's
        // invariant: secrets exist iff a login exists) — so this may or may not need a new password.
        HttpResponse<String> setKey = send(browser, post("/setup/sources/sports/thesportsdb/key", Map.of(
                "key", FakeTheSportsDbServer.PERSONAL_KEY, "loginPassword", LOGIN, "loginPasswordConfirmation", LOGIN)));
        assertThat(setKey.statusCode()).isEqualTo(302);
        String afterKey = send(browser, page("/setup")).body();
        assertThat(afterKey).contains("Using your personal key.").doesNotContain(FakeTheSportsDbServer.PERSONAL_KEY);

        assertThat(send(browser, post("/rails/sports/live-today/refresh", Map.of())).statusCode()).isEqualTo(200);
        await().atMost(Duration.ofSeconds(10)).until(() ->
                SPORTSDB.requests("eventsday.php").stream().anyMatch(r -> FakeTheSportsDbServer.PERSONAL_KEY.equals(r.key())));
        assertThat(secrets.names()).contains("sports.thesportsdb.key");

        int beforeFreeKey = SPORTSDB.count("eventsday.php");
        assertThat(send(browser, post("/setup/sources/sports/thesportsdb/free-key", Map.of())).statusCode()).isEqualTo(302);
        assertThat(secrets.names()).doesNotContain("sports.thesportsdb.key");
        assertThat(send(browser, post("/rails/sports/live-today/refresh", Map.of())).statusCode()).isEqualTo(200);
        await().atMost(Duration.ofSeconds(10)).until(() -> SPORTSDB.count("eventsday.php") > beforeFreeKey);
        assertThat(SPORTSDB.last("eventsday.php").key()).isEqualTo("123");

        stopServers();
    }
}

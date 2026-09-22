package dev.andre.homecontrol.web;

import dev.andre.homecontrol.sources.youtube.FakeGoogleServer;
import dev.andre.homecontrol.sources.youtube.GoogleTokens;
import dev.andre.homecontrol.sources.youtube.YouTubeSettings;
import dev.andre.homecontrol.sources.youtube.YouTubeSetupService;
import dev.andre.homecontrol.content.RailCache;
import dev.andre.homecontrol.storage.SecretStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.CookieManager;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Collectors;

import static dev.andre.homecontrol.sources.youtube.YouTubeHttp.form;
import static org.assertj.core.api.Assertions.assertThat;

/** Real HTTP, sessions, filters, encrypted storage and Google token requests. Google alone is faked. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class YouTubeBrowserOAuthTest {
    static final FakeGoogleServer GOOGLE;
    static final Path DATA;
    static {
        try {
            GOOGLE = new FakeGoogleServer();
            DATA = Files.createTempDirectory("youtube-browser-oauth");
            GOOGLE.youtubeLibrary();
            GOOGLE.respond("POST", "/oauth/token", FakeGoogleServer.Canned.fixture(200, "oauth-token-granted.json"));
            GOOGLE.respond("POST", "/oauth/revoke", FakeGoogleServer.Canned.json(200, "{}"));
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("shield.data-dir", DATA::toString);
        properties.add("home-control.youtube.oauth-base-url", () -> GOOGLE.base() + "/oauth");
        properties.add("home-control.youtube.api-base-url", () -> GOOGLE.base() + "/youtube/v3");
        properties.add("home-control.content.rails.scheduler-enabled", () -> "false");
    }

    @AfterAll
    static void cleanup() throws Exception {
        GOOGLE.close();
        try (var paths = Files.walk(DATA)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    @LocalServerPort int port;
    @Autowired SecretStore secrets;
    @Autowired GoogleTokens tokens;
    @Autowired YouTubeSetupService setup;
    @Autowired RailCache rails;
    final HttpClient browser = browser();

    static HttpClient browser() {
        return HttpClient.newBuilder().cookieHandler(new CookieManager()).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    HttpResponse<String> get(HttpClient client, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> post(HttpClient client, String path, Map<String, String> values) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Origin", "http://localhost:" + port)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form(values))).build(), HttpResponse.BodyHandlers.ofString());
    }

    static Map<String, String> query(URI uri) {
        return Arrays.stream(uri.getRawQuery().split("&")).map(part -> part.split("=", 2))
                .collect(Collectors.toMap(p -> p[0], p -> URLDecoder.decode(p[1], StandardCharsets.UTF_8)));
    }

    Map<String, String> start() throws Exception {
        var response = post(browser, "/setup/sources/youtube/browser/authorize", Map.of());
        assertThat(response.statusCode()).isEqualTo(302);
        return query(URI.create(response.headers().firstValue("Location").orElseThrow()));
    }

    String callback(Map<String, String> request, Map<String, String> result) {
        return "/setup/sources/youtube/callback?state=" + request.get("state") + "&" + form(result);
    }

    @Test
    void browserConsentStoresAndRefreshesTokensAndRejectsForeignCancelledAndReplayedCallbacks() throws Exception {
        var response = post(browser, "/setup/sources/youtube/browser/connect", Map.of(
                "clientId", "123456789012-abc123def456.apps.googleusercontent.com",
                "clientSecret", "GOCSPX-fixtureClientSecret", "loginPassword", "correct-horse-1",
                "loginPasswordConfirmation", "correct-horse-1"));
        assertThat(response.statusCode()).isEqualTo(302);
        URI location = URI.create(response.headers().firstValue("Location").orElseThrow());
        assertThat(location.getHost()).isEqualTo("accounts.google.com");
        Map<String, String> auth = query(location);
        assertThat(auth).containsEntry("response_type", "code").containsEntry("access_type", "offline")
                .containsEntry("scope", "https://www.googleapis.com/auth/youtube.readonly")
                .containsEntry("prompt", "consent select_account").containsEntry("code_challenge_method", "S256")
                .containsEntry("redirect_uri", "http://localhost:" + port + "/setup/sources/youtube/callback");
        assertThat(auth.get("state")).hasSizeGreaterThanOrEqualTo(43);
        assertThat(location.toString()).doesNotContain("GOCSPX", "code_verifier");
        assertThat(GOOGLE.count("/oauth/device/code")).isZero();

        HttpClient other = browser();
        assertThat(get(other, callback(auth, Map.of("code", "foreign-code"))).statusCode()).isEqualTo(401);
        post(other, "/login", Map.of("password", "correct-horse-1"));
        get(other, callback(auth, Map.of("code", "foreign-code")));
        get(browser, "/setup/sources/youtube/callback?state=wrong&code=foreign-code");
        assertThat(GOOGLE.count("/oauth/token")).isZero();

        var completed = get(browser, callback(auth, Map.of("code", "one-time-code")));
        assertThat(completed.statusCode()).isEqualTo(302);
        assertThat(completed.headers().firstValue("Location")).hasValue("http://localhost:" + port + "/setup#youtube");
        assertThat(completed.headers().firstValue("Cache-Control")).hasValue("no-store");
        assertThat(completed.headers().firstValue("Referrer-Policy")).hasValue("no-referrer");
        var exchange = GOOGLE.requests("/oauth/token").getFirst().form();
        assertThat(exchange).containsEntry("grant_type", "authorization_code").containsEntry("code", "one-time-code")
                .containsEntry("redirect_uri", auth.get("redirect_uri"));
        assertThat(Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256")
                .digest(exchange.get("code_verifier").getBytes(StandardCharsets.US_ASCII))))
                .isEqualTo(auth.get("code_challenge"));
        assertThat(secrets.secret(YouTubeSettings.REFRESH_TOKEN)).contains("1//0gFixtureRefreshTokenGranted-0001");
        assertThat(Files.readString(DATA.resolve("secrets.json"))).doesNotContain("GOCSPX", "1//0g", "ya29.");
        assertThat(get(browser, "/setup").body()).contains("Connected as Andre at Home")
                .doesNotContain("GOCSPX", "1//0g", "ya29.", "one-time-code");
        get(browser, callback(auth, Map.of("code", "one-time-code")));
        assertThat(GOOGLE.count("/oauth/token")).isEqualTo(1);

        GOOGLE.respond("POST", "/oauth/token", FakeGoogleServer.Canned.fixture(200, "oauth-refresh-granted.json"));
        tokens.invalidate();
        assertThat(tokens.accessToken()).isEqualTo("ya29.a0AfB_byFixtureAccessTokenRefreshed02");
        assertThat(GOOGLE.requests("/oauth/token").getLast().form()).containsEntry("grant_type", "refresh_token");
        int count = GOOGLE.count("/oauth/token");

        var denied = start();
        get(browser, callback(denied, Map.of("error", "access_denied", "error_description", "do-not-echo")));
        assertThat(get(browser, "/setup").body()).contains("denied").doesNotContain("do-not-echo");
        var cancelled = start();
        post(browser, "/setup/sources/youtube/cancel", Map.of());
        get(browser, callback(cancelled, Map.of("code", "cancelled-code")));
        assertThat(GOOGLE.count("/oauth/token")).isEqualTo(count);

        // Reconnecting the same account retains rail choices; choosing another clears them and outer snapshots.
        setup.save(setup.settings().withPlaylists(Map.of("PLold", "Keep my playlist")).withWatchLater(true));
        GOOGLE.respond("POST", "/oauth/token", FakeGoogleServer.Canned.fixture(200, "oauth-token-granted.json"));
        get(browser, callback(start(), Map.of("code", "same-account-code")));
        assertThat(setup.settings().playlists()).containsEntry("PLold", "Keep my playlist");
        assertThat(setup.settings().watchLater()).isTrue();
        rails.snapshots();
        assertThat(rails.peek()).anyMatch(rail -> rail.sourceId().equals("youtube"));
        GOOGLE.respond("GET", "/youtube/v3/channels", FakeGoogleServer.Canned.json(200,
                FakeGoogleServer.fixture("channels-mine.json").replace("UC4fixtureHomeControl00a", "another-channel")
                        .replace("Andre at Home", "Another account")));
        get(browser, callback(start(), Map.of("code", "different-account-code")));
        assertThat(setup.settings().channelTitle()).isEqualTo("Another account");
        assertThat(setup.settings().playlists()).isEmpty();
        assertThat(setup.settings().watchLater()).isFalse();
        assertThat(rails.peek()).noneMatch(rail -> rail.sourceId().equals("youtube"));
        count = GOOGLE.count("/oauth/token");

        var pending = start();
        post(browser, "/setup/sources/youtube/disconnect", Map.of());
        get(browser, callback(pending, Map.of("code", "disconnected-code")));
        assertThat(secrets.secret(YouTubeSettings.REFRESH_TOKEN)).isEmpty();
        assertThat(GOOGLE.count("/oauth/token")).isEqualTo(count);
        assertThat(GOOGLE.count("/oauth/revoke")).isEqualTo(1);
    }
}

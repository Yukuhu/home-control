package dev.andre.homecontrol.sources.youtube;

import dev.andre.homecontrol.core.content.ContentSourceException;
import dev.andre.homecontrol.testsupport.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GoogleOAuthClientTest {

    private FakeGoogleServer fake;
    private GoogleOAuthClient client;

    private void start() throws IOException {
        fake = new FakeGoogleServer();
        MutableClock clock = MutableClock.at(Instant.parse("2026-09-16T10:00:00Z"));
        client = new GoogleOAuthClient(new YouTubeHttp(fake.properties()), URI.create(fake.base() + "/oauth"), clock);
    }

    @AfterEach
    void closeFake() {
        if (fake != null) {
            fake.close();
        }
    }

    @Test
    void requestsADeviceCode() throws IOException {
        start();
        fake.respond("POST", "/oauth/device/code", FakeGoogleServer.Canned.fixture(200, "oauth-device-code.json"));

        GoogleOAuthClient.DeviceCode code = client.requestDeviceCode("123-abc.apps.googleusercontent.com");

        assertThat(code.userCode()).isEqualTo("GQVQ-JKEC");
        assertThat(code.verificationUrl()).isEqualTo(URI.create("https://www.google.com/device"));
        assertThat(code.expiresAt()).isEqualTo(Instant.parse("2026-09-16T10:30:00Z"));
        assertThat(code.interval()).isEqualTo(Duration.ofSeconds(5));

        String body = fake.requests("/oauth/device/code").getFirst().body();
        assertThat(body).isEqualTo("client_id=123-abc.apps.googleusercontent.com"
                + "&scope=https%3A%2F%2Fwww.googleapis.com%2Fauth%2Fyoutube.readonly");
    }

    @Test
    void deviceCodeToStringHidesTheCode() throws IOException {
        start();
        fake.respond("POST", "/oauth/device/code", FakeGoogleServer.Canned.fixture(200, "oauth-device-code.json"));

        GoogleOAuthClient.DeviceCode code = client.requestDeviceCode("cid");

        assertThat(code.toString()).doesNotContain("AH-1Ng2m");
    }

    @Test
    void pendingIsMapped() throws IOException {
        start();
        fake.respond("POST", "/oauth/token", FakeGoogleServer.Canned.fixture(428, "oauth-token-pending.json"));

        assertThat(client.poll("cid", "csecret", "dc")).isInstanceOf(GoogleOAuthClient.TokenPoll.Pending.class);
    }

    @Test
    void slowDownIsMapped() throws IOException {
        start();
        fake.respond("POST", "/oauth/token", FakeGoogleServer.Canned.fixture(403, "oauth-token-slow-down.json"));

        assertThat(client.poll("cid", "csecret", "dc")).isInstanceOf(GoogleOAuthClient.TokenPoll.SlowDown.class);
    }

    @Test
    void deniedIsMapped() throws IOException {
        start();
        fake.respond("POST", "/oauth/token", FakeGoogleServer.Canned.fixture(403, "oauth-token-denied.json"));

        assertThat(client.poll("cid", "csecret", "dc")).isInstanceOf(GoogleOAuthClient.TokenPoll.Denied.class);
    }

    @Test
    void expiredIsMapped() throws IOException {
        start();
        fake.respond("POST", "/oauth/token", FakeGoogleServer.Canned.fixture(400, "oauth-token-expired.json"));

        assertThat(client.poll("cid", "csecret", "dc")).isInstanceOf(GoogleOAuthClient.TokenPoll.Expired.class);
    }

    @Test
    void anUnknownErrorIsFailed() throws IOException {
        start();
        fake.respond("POST", "/oauth/token", FakeGoogleServer.Canned.json(400,
                "{\"error\":\"invalid_grant\",\"error_description\":\"Malformed auth code.\"}"));

        GoogleOAuthClient.TokenPoll result = client.poll("cid", "csecret", "dc");

        assertThat(result).isInstanceOf(GoogleOAuthClient.TokenPoll.Failed.class);
        GoogleOAuthClient.TokenPoll.Failed failed = (GoogleOAuthClient.TokenPoll.Failed) result;
        assertThat(failed.error()).isEqualTo("invalid_grant");
        assertThat(failed.description()).isEqualTo("Malformed auth code.");
    }

    @Test
    void aGrantIsMapped() throws IOException {
        start();
        fake.respond("POST", "/oauth/token", FakeGoogleServer.Canned.fixture(200, "oauth-token-granted.json"));

        GoogleOAuthClient.TokenPoll result = client.poll("cid", "csecret", "dc");

        assertThat(result).isInstanceOf(GoogleOAuthClient.TokenPoll.Granted.class);
        GoogleOAuthClient.TokenPoll.Granted granted = (GoogleOAuthClient.TokenPoll.Granted) result;
        assertThat(granted.accessToken().value()).isEqualTo("ya29.a0AfB_byFixtureAccessTokenGranted0001");
        assertThat(granted.accessToken().expiresAt()).isEqualTo(Instant.parse("2026-09-16T10:59:59Z"));
        assertThat(granted.refreshToken()).isEqualTo("1//0gFixtureRefreshTokenGranted-0001");
    }

    @Test
    void pollSendsTheDeviceGrant() throws IOException {
        start();
        fake.respond("POST", "/oauth/token", FakeGoogleServer.Canned.fixture(200, "oauth-token-granted.json"));

        client.poll("cid", "csecret", "AH-1");

        String body = fake.requests("/oauth/token").getFirst().body();
        assertThat(body).isEqualTo("client_id=cid&client_secret=csecret&device_code=AH-1"
                + "&grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Adevice_code");
    }

    @Test
    void aWrongClientTypeIsExplained() throws IOException {
        start();
        fake.respond("POST", "/oauth/device/code", FakeGoogleServer.Canned.fixture(401, "oauth-invalid-client-type.json"));

        assertThatThrownBy(() -> client.requestDeviceCode("cid"))
                .isInstanceOf(YouTubeException.class)
                .extracting(e -> ((YouTubeException) e).kind())
                .isEqualTo(ContentSourceException.Kind.UNAUTHORIZED);
        assertThatThrownBy(() -> client.requestDeviceCode("cid"))
                .hasMessageContaining("TVs and Limited Input devices");
    }

    @Test
    void refreshUsesTheRefreshGrant() throws IOException {
        start();
        fake.respond("POST", "/oauth/token", FakeGoogleServer.Canned.fixture(200, "oauth-refresh-granted.json"));

        GoogleOAuthClient.AccessToken token = client.refresh("cid", "csecret", "1//0gX");

        assertThat(token.value()).isEqualTo("ya29.a0AfB_byFixtureAccessTokenRefreshed02");
        String body = fake.requests("/oauth/token").getFirst().body();
        assertThat(body).isEqualTo("client_id=cid&client_secret=csecret&refresh_token=1%2F%2F0gX&grant_type=refresh_token");
    }

    @Test
    void aRevokedRefreshTokenIsRevoked() throws IOException {
        start();
        fake.respond("POST", "/oauth/token", FakeGoogleServer.Canned.fixture(400, "oauth-refresh-invalid-grant.json"));

        assertThatThrownBy(() -> client.refresh("cid", "csecret", "rt"))
                .isInstanceOf(YouTubeException.class)
                .extracting(e -> ((YouTubeException) e).kind())
                .isEqualTo(ContentSourceException.Kind.REVOKED);
        assertThatThrownBy(() -> client.refresh("cid", "csecret", "rt"))
                .hasMessageContaining("Testing")
                .hasMessageContaining("Reconnect YouTube");
    }

    @Test
    void aGrantWithoutRefreshTokenFails() throws IOException {
        start();
        fake.respond("POST", "/oauth/token", FakeGoogleServer.Canned.fixture(200, "oauth-refresh-granted.json"));

        GoogleOAuthClient.TokenPoll result = client.poll("cid", "csecret", "dc");

        assertThat(result).isInstanceOf(GoogleOAuthClient.TokenPoll.Failed.class);
        assertThat(((GoogleOAuthClient.TokenPoll.Failed) result).error()).isEqualTo("no_refresh_token");
    }

    @Test
    // The production poll floor is the actual value; Duration.ofSeconds(1) is the independent expectation.
    @SuppressWarnings("java:S3415")
    void theReportedPollIntervalNeverGoesBelowTheFloor() throws IOException {
        start();
        String immediate = FakeGoogleServer.fixture("oauth-device-code.json").replace("\"interval\": 5", "\"interval\": 0");
        fake.respond("POST", "/oauth/device/code", FakeGoogleServer.Canned.json(200, immediate));

        assertThat(client.requestDeviceCode("cid").interval()).isEqualTo(GoogleOAuthClient.MINIMUM_POLL_INTERVAL);

        GoogleOAuthClient fast = new GoogleOAuthClient(new YouTubeHttp(fake.properties()), URI.create(fake.base() + "/oauth"),
                MutableClock.at(Instant.parse("2026-09-16T10:00:00Z")), Duration.ofMillis(10));
        assertThat(fast.requestDeviceCode("cid").interval()).isEqualTo(Duration.ofMillis(10));
        assertThat(GoogleOAuthClient.MINIMUM_POLL_INTERVAL).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void grantedToStringIsRedacted() throws IOException {
        start();
        fake.respond("POST", "/oauth/token", FakeGoogleServer.Canned.fixture(200, "oauth-token-granted.json"));

        GoogleOAuthClient.TokenPoll.Granted granted = (GoogleOAuthClient.TokenPoll.Granted) client.poll("cid", "csecret", "dc");

        assertThat(granted.toString()).doesNotContain("ya29.a0AfB_byFixtureAccessTokenGranted0001")
                .doesNotContain("1//0gFixtureRefreshTokenGranted-0001");
        assertThat(granted.accessToken().toString()).doesNotContain("ya29.a0AfB_byFixtureAccessTokenGranted0001");
    }

    @Test
    void aDeviceCodeAnswerWithoutTheCodesIsABadResponse() throws IOException {
        start();
        fake.respond("POST", "/oauth/device/code", FakeGoogleServer.Canned.json(200, "{\"device_code\":\"dc\"}"));

        assertThatThrownBy(() -> client.requestDeviceCode("cid"))
                .isInstanceOf(YouTubeException.class)
                .hasMessage("Google did not return a device code")
                .extracting(e -> ((YouTubeException) e).kind())
                .isEqualTo(ContentSourceException.Kind.BAD_RESPONSE);
    }

    @Test
    void aDeviceCodeWithoutAVerificationUrlUsesGooglesDevicePage() throws IOException {
        start();
        fake.respond("POST", "/oauth/device/code", FakeGoogleServer.Canned.json(200,
                "{\"device_code\":\"dc\",\"user_code\":\"ABCD-EFGH\"}"));

        GoogleOAuthClient.DeviceCode code = client.requestDeviceCode("cid");

        assertThat(code.verificationUrl()).isEqualTo(GoogleOAuthClient.DEFAULT_VERIFICATION_URL);
        assertThat(code.expiresAt()).isEqualTo(Instant.parse("2026-09-16T10:30:00Z"));
        assertThat(code.interval()).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void aPollAnsweredWithoutAnErrorCodeNamesTheStatus() throws IOException {
        start();
        fake.respond("POST", "/oauth/token", FakeGoogleServer.Canned.json(502, "<html>Bad gateway</html>"),
                FakeGoogleServer.Canned.json(400, "{}"));

        assertThatThrownBy(() -> client.poll("cid", "csecret", "dc"))
                .isInstanceOf(YouTubeException.class)
                .hasMessage("Google answered HTTP 502")
                .extracting(e -> ((YouTubeException) e).kind())
                .isEqualTo(ContentSourceException.Kind.SERVER_ERROR);
        assertThatThrownBy(() -> client.poll("cid", "csecret", "dc"))
                .hasMessage("Google answered HTTP 400")
                .extracting(e -> ((YouTubeException) e).kind())
                .isEqualTo(ContentSourceException.Kind.BAD_RESPONSE);
    }

    @Test
    void aRejectedClientEndsThePollWithoutEchoingGoogle() throws IOException {
        start();
        fake.respond("POST", "/oauth/token", FakeGoogleServer.Canned.json(401,
                "{\"error\":\"invalid_client\",\"error_description\":\"The OAuth client was not found.\"}"));

        assertThatThrownBy(() -> client.poll("cid", "csecret", "dc"))
                .isInstanceOf(YouTubeException.class)
                .hasMessage("Google rejected the client ID or client secret.")
                .extracting(e -> ((YouTubeException) e).kind())
                .isEqualTo(ContentSourceException.Kind.UNAUTHORIZED);
    }

    @Test
    void aRefreshRefusedForAnotherReasonNamesTheError() throws IOException {
        start();
        fake.respond("POST", "/oauth/token", FakeGoogleServer.Canned.json(400, "{\"error\":\"unsupported_grant_type\"}"),
                FakeGoogleServer.Canned.json(503, "unavailable"));

        assertThatThrownBy(() -> client.refresh("cid", "csecret", "rt"))
                .isInstanceOf(YouTubeException.class)
                .hasMessage("Google refused the request (unsupported_grant_type)")
                .extracting(e -> ((YouTubeException) e).kind())
                .isEqualTo(ContentSourceException.Kind.BAD_RESPONSE);
        assertThatThrownBy(() -> client.refresh("cid", "csecret", "rt"))
                .hasMessage("Google answered HTTP 503")
                .extracting(e -> ((YouTubeException) e).kind())
                .isEqualTo(ContentSourceException.Kind.SERVER_ERROR);
    }

    @Test
    void aBrowserCodeExchangeNeedsTheReadOnlyScopeAndARefreshToken() throws IOException {
        start();
        URI callback = URI.create("https://home.example.com/setup/sources/youtube/callback");
        fake.respond("POST", "/oauth/token",
                FakeGoogleServer.Canned.json(200, "{\"access_token\":\"at\",\"refresh_token\":\"rt\","
                        + "\"scope\":\"openid " + GoogleOAuthClient.SCOPE + "\"}"),
                FakeGoogleServer.Canned.json(200, "{\"access_token\":\"at\",\"refresh_token\":\"rt\","
                        + "\"scope\":\"openid email\"}"),
                FakeGoogleServer.Canned.json(200, "{\"access_token\":\"at\"}"));

        GoogleOAuthClient.TokenPoll.Granted granted = client.exchangeCode("cid", "csecret", "code", callback, "verifier");
        assertThat(granted.refreshToken()).isEqualTo("rt");
        assertThat(granted.accessToken().value()).isEqualTo("at");
        assertThat(granted.accessToken().expiresAt()).isEqualTo(Instant.parse("2026-09-16T11:00:00Z"));
        assertThat(fake.requests("/oauth/token").getFirst().body())
                .contains("grant_type=authorization_code", "code_verifier=verifier",
                        "redirect_uri=https%3A%2F%2Fhome.example.com%2Fsetup%2Fsources%2Fyoutube%2Fcallback");

        assertThatThrownBy(() -> client.exchangeCode("cid", "csecret", "code", callback, "verifier"))
                .hasMessageContaining("YouTube read-only access was not granted")
                .extracting(e -> ((YouTubeException) e).kind())
                .isEqualTo(ContentSourceException.Kind.UNAUTHORIZED);
        assertThatThrownBy(() -> client.exchangeCode("cid", "csecret", "code", callback, "verifier"))
                .hasMessageContaining("Google did not return offline access");
    }

    @Test
    void aGrantWithoutAnAccessTokenIsABadResponse() throws IOException {
        start();
        fake.respond("POST", "/oauth/token", FakeGoogleServer.Canned.json(200, "{\"refresh_token\":\"rt\"}"));

        assertThatThrownBy(() -> client.poll("cid", "csecret", "dc"))
                .isInstanceOf(YouTubeException.class)
                .hasMessage("Google did not return an access token");
    }

    @Test
    void theBrowserConsentPageAsksForOfflineReadOnlyAccessWithPkce() throws IOException {
        start();

        URI url = client.authorizationUrl("cid", URI.create("https://home.example.com/cb"), "the-state", "the-challenge");

        assertThat(url.getHost()).isEqualTo("accounts.google.com");
        assertThat(url.getPath()).isEqualTo("/o/oauth2/v2/auth");
        assertThat(url.getQuery()).contains("client_id=cid", "redirect_uri=https://home.example.com/cb",
                "response_type=code", "access_type=offline", "state=the-state", "code_challenge=the-challenge",
                "code_challenge_method=S256", "scope=" + GoogleOAuthClient.SCOPE);
    }
}

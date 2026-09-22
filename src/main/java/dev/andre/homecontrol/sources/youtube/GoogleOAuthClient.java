package dev.andre.homecontrol.sources.youtube;

import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Google's OAuth 2.0 device and browser authorization grants. */
public class GoogleOAuthClient {

    public static final String SCOPE = "https://www.googleapis.com/auth/youtube.readonly";
    public static final String DEVICE_GRANT = "urn:ietf:params:oauth:grant-type:device_code";
    static final URI DEFAULT_VERIFICATION_URL = URI.create("https://www.google.com/device");

    public record DeviceCode(String deviceCode, String userCode, URI verificationUrl, Instant expiresAt, Duration interval) {
        @Override
        public String toString() {
            return "DeviceCode[userCode=" + userCode + ", expiresAt=" + expiresAt + ", interval=" + interval + "]";
        }
    }

    public record AccessToken(String value, Instant expiresAt) {
        @Override
        public String toString() {
            return "AccessToken[expiresAt=" + expiresAt + "]";
        }
    }

    public sealed interface TokenPoll {
        record Granted(AccessToken accessToken, String refreshToken) implements TokenPoll {
            @Override
            public String toString() {
                return "Granted[" + accessToken + "]";
            }
        }

        record Pending() implements TokenPoll {
        }

        record SlowDown() implements TokenPoll {
        }

        record Denied() implements TokenPoll {
        }

        record Expired() implements TokenPoll {
        }

        record Failed(String error, String description) implements TokenPoll {
        }
    }

    private final YouTubeHttp http;
    private final URI base;
    private final Clock clock;

    public GoogleOAuthClient(YouTubeHttp http, URI oauthBaseUrl, Clock clock) {
        this.http = http;
        this.base = oauthBaseUrl;
        this.clock = clock;
    }

    public DeviceCode requestDeviceCode(String clientId) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("client_id", clientId);
        form.put("scope", SCOPE);
        YouTubeHttp.Response response = http.postForm(URI.create(base + "/device/code"), form, Map.of());
        if (!response.ok()) {
            throw failure(response);
        }
        JsonNode json = response.json();
        String deviceCode = json.path("device_code").asString("");
        String userCode = json.path("user_code").asString("");
        if (deviceCode.isBlank() || userCode.isBlank()) {
            throw new YouTubeException(YouTubeException.Kind.BAD_RESPONSE, "Google did not return a device code");
        }
        String url = json.path("verification_url").asString("");
        return new DeviceCode(deviceCode, userCode,
                url.isBlank() ? DEFAULT_VERIFICATION_URL : URI.create(url),
                clock.instant().plusSeconds(json.path("expires_in").asLong(1800)),
                Duration.ofSeconds(Math.max(1, json.path("interval").asLong(5))));
    }

    public URI authorizationUrl(String clientId, URI redirectUri, String state, String challenge) {
        return YouTubeHttp.uri(URI.create("https://accounts.google.com"), "/o/oauth2/v2/auth", Map.of(
                "client_id", clientId, "redirect_uri", redirectUri.toString(), "response_type", "code",
                "scope", SCOPE, "access_type", "offline", "prompt", "consent select_account",
                "state", state, "code_challenge", challenge, "code_challenge_method", "S256"));
    }

    public TokenPoll.Granted exchangeCode(String clientId, String clientSecret, String code,
                                          URI redirectUri, String verifier) {
        YouTubeHttp.Response response = http.postForm(URI.create(base + "/token"), Map.of(
                "client_id", clientId, "client_secret", clientSecret, "code", code,
                "redirect_uri", redirectUri.toString(), "code_verifier", verifier,
                "grant_type", "authorization_code"), Map.of());
        if (!response.ok()) {
            // Never echo Google error descriptions or codes: they may contain submitted credentials.
            throw new YouTubeException(YouTubeException.Kind.BAD_RESPONSE,
                    "Google could not complete sign-in. Check the Web application client and callback URL, then try again.");
        }
        JsonNode json = response.json();
        String scope = json.path("scope").asString("");
        if (!scope.isBlank() && java.util.Arrays.stream(scope.split("\\s+")).noneMatch(SCOPE::equals)) {
            throw new YouTubeException(YouTubeException.Kind.UNAUTHORIZED,
                    "YouTube read-only access was not granted. Sign in again and allow access to YouTube.");
        }
        String refresh = json.path("refresh_token").asString("");
        if (refresh.isBlank()) {
            throw new YouTubeException(YouTubeException.Kind.BAD_RESPONSE,
                    "Google did not return offline access. Sign in again and accept the consent request.");
        }
        return new TokenPoll.Granted(accessToken(json), refresh);
    }

    public TokenPoll poll(String clientId, String clientSecret, String deviceCode) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("client_id", clientId);
        form.put("client_secret", clientSecret);
        form.put("device_code", deviceCode);
        form.put("grant_type", DEVICE_GRANT);
        YouTubeHttp.Response response = http.postForm(URI.create(base + "/token"), form, Map.of());
        if (response.ok()) {
            JsonNode json = response.json();
            String refresh = json.path("refresh_token").asString("");
            if (refresh.isBlank()) {
                return new TokenPoll.Failed("no_refresh_token", "Google did not return a refresh token");
            }
            return new TokenPoll.Granted(accessToken(json), refresh);
        }
        String error = errorCode(response);
        return switch (error) {
            case "authorization_pending" -> new TokenPoll.Pending();
            case "slow_down" -> new TokenPoll.SlowDown();
            case "access_denied" -> new TokenPoll.Denied();
            case "expired_token" -> new TokenPoll.Expired();
            case "" -> throw failure(response);
            case "invalid_client" -> throw failure(response);
            default -> new TokenPoll.Failed(error, response.json().path("error_description").asString(""));
        };
    }

    public AccessToken refresh(String clientId, String clientSecret, String refreshToken) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("client_id", clientId);
        form.put("client_secret", clientSecret);
        form.put("refresh_token", refreshToken);
        form.put("grant_type", "refresh_token");
        YouTubeHttp.Response response = http.postForm(URI.create(base + "/token"), form, Map.of());
        if (response.ok()) {
            return accessToken(response.json());
        }
        if ("invalid_grant".equals(errorCode(response))) {
            throw new YouTubeException(YouTubeException.Kind.REVOKED, "Google no longer accepts the saved YouTube"
                    + " authorization. It was revoked, or it expired after 7 days because the OAuth consent screen"
                    + " is still in “Testing”. Reconnect YouTube on the setup page.", "invalid_grant");
        }
        throw failure(response);
    }

    public void revoke(String token) {
        http.postForm(URI.create(base + "/revoke"), Map.of("token", token), Map.of());
    }

    private AccessToken accessToken(JsonNode json) {
        String value = json.path("access_token").asString("");
        if (value.isBlank()) {
            throw new YouTubeException(YouTubeException.Kind.BAD_RESPONSE, "Google did not return an access token");
        }
        return new AccessToken(value, clock.instant().plusSeconds(json.path("expires_in").asLong(3600)));
    }

    private static String errorCode(YouTubeHttp.Response response) {
        try {
            return response.json().path("error").asString("");
        } catch (YouTubeException notJson) {
            return "";
        }
    }

    private static YouTubeException failure(YouTubeHttp.Response response) {
        String error = errorCode(response);
        if ("invalid_client".equals(error)) {
            String description = response.json().path("error_description").asString("").toLowerCase(Locale.ROOT);
            return new YouTubeException(YouTubeException.Kind.UNAUTHORIZED, description.contains("client type")
                    ? "Google says this OAuth client cannot use the device flow. Create a client of type"
                    + " “TVs and Limited Input devices”."
                    : "Google rejected the client ID or client secret.", error);
        }
        if (!error.isEmpty()) {
            return new YouTubeException(YouTubeException.Kind.BAD_RESPONSE, "Google refused the request (" + error + ")", error);
        }
        return new YouTubeException(response.status() >= 500 ? YouTubeException.Kind.SERVER_ERROR
                : YouTubeException.Kind.BAD_RESPONSE, "Google answered HTTP " + response.status());
    }
}

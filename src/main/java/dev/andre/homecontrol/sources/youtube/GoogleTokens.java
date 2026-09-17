package dev.andre.homecontrol.sources.youtube;

import dev.andre.homecontrol.storage.SecretStore;

import java.time.Clock;
import java.time.Duration;

/** The access token lives only here, in memory. */
public class GoogleTokens {

    private static final Duration EARLY = Duration.ofSeconds(60);

    private final GoogleOAuthClient oauth;
    private final SecretStore secrets;
    private final Clock clock;
    private GoogleOAuthClient.AccessToken current;
    private YouTubeException revoked;

    public GoogleTokens(GoogleOAuthClient oauth, SecretStore secrets, Clock clock) {
        this.oauth = oauth;
        this.secrets = secrets;
        this.clock = clock;
    }

    public synchronized String accessToken() {
        if (revoked != null) {
            throw revoked;
        }
        if (current != null && clock.instant().isBefore(current.expiresAt().minus(EARLY))) {
            return current.value();
        }
        String clientId = secrets.secret(YouTubeSettings.CLIENT_ID).orElse(null);
        String clientSecret = secrets.secret(YouTubeSettings.CLIENT_SECRET).orElse(null);
        String refreshToken = secrets.secret(YouTubeSettings.REFRESH_TOKEN).orElse(null);
        if (clientId == null || clientSecret == null || refreshToken == null) {
            throw new YouTubeException(YouTubeException.Kind.NOT_CONFIGURED, "YouTube is not connected");
        }
        try {
            current = oauth.refresh(clientId, clientSecret, refreshToken);
            return current.value();
        } catch (YouTubeException e) {
            if (e.kind() == YouTubeException.Kind.REVOKED) {
                revoked = e;
            }
            throw e;
        }
    }

    public synchronized void prime(GoogleOAuthClient.AccessToken token) {
        current = token;
    }

    public synchronized void invalidate() {
        current = null;
    }

    public synchronized void reset() {
        current = null;
        revoked = null;
    }

    public synchronized boolean revoked() {
        return revoked != null;
    }

    public boolean hasClient() {
        return secrets.secret(YouTubeSettings.CLIENT_ID).isPresent() && secrets.secret(YouTubeSettings.CLIENT_SECRET).isPresent();
    }

    public boolean hasRefreshToken() {
        return secrets.secret(YouTubeSettings.REFRESH_TOKEN).isPresent();
    }
}

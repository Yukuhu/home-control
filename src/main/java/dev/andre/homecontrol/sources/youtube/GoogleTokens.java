package dev.andre.homecontrol.sources.youtube;

import dev.andre.homecontrol.core.content.ContentSourceException;
import dev.andre.homecontrol.storage.SecretStore;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * The access token lives only here, in memory.
 *
 * <p>The monitor guards only the fields, never a call to Google: a refresh runs outside it, once for every caller
 * that needs a token meanwhile, so the setup page and the other sources never wait for Google. A reset, a primed token
 * or an invalidation starts a new generation; a refresh begun before it serves only the callers already waiting.
 */
public class GoogleTokens {

    private static final Duration EARLY = Duration.ofSeconds(60);

    private final GoogleOAuthClient oauth;
    private final SecretStore secrets;
    private final Clock clock;
    private GoogleOAuthClient.AccessToken current;
    // Set once to the refusal Google sent, and only ever replaced whole: the volatile reference is all readers need.
    @SuppressWarnings("java:S3077")
    private volatile YouTubeException revoked;
    /** The refresh under way, which callers needing a token join; null when none is. */
    private CompletableFuture<GoogleOAuthClient.AccessToken> refreshing;
    private long generation;

    public GoogleTokens(GoogleOAuthClient oauth, SecretStore secrets, Clock clock) {
        this.oauth = oauth;
        this.secrets = secrets;
        this.clock = clock;
    }

    public String accessToken() {
        CompletableFuture<GoogleOAuthClient.AccessToken> refresh;
        boolean mine = false;
        long started;
        synchronized (this) {
            YouTubeException refused = revoked;
            if (refused != null) {
                throw refused;
            }
            if (current != null && clock.instant().isBefore(current.expiresAt().minus(EARLY))) {
                return current.value();
            }
            if (refreshing == null) {
                refreshing = new CompletableFuture<>();
                mine = true;
            }
            refresh = refreshing;
            started = generation;
        }
        if (mine) {
            refresh(refresh, started);
        }
        return await(refresh).value();
    }

    /** Asks Google outside the monitor, then keeps the token only if nothing reset or primed it meanwhile. */
    private void refresh(CompletableFuture<GoogleOAuthClient.AccessToken> refresh, long started) {
        try {
            String clientId = secrets.secret(YouTubeSettings.CLIENT_ID).orElse(null);
            String clientSecret = secrets.secret(YouTubeSettings.CLIENT_SECRET).orElse(null);
            String refreshToken = secrets.secret(YouTubeSettings.REFRESH_TOKEN).orElse(null);
            if (clientId == null || clientSecret == null || refreshToken == null) {
                throw new YouTubeException(ContentSourceException.Kind.NOT_CONFIGURED, "YouTube is not connected");
            }
            GoogleOAuthClient.AccessToken token = oauth.refresh(clientId, clientSecret, refreshToken);
            synchronized (this) {
                if (generation == started) {
                    current = token;
                }
                finished(refresh);
            }
            refresh.complete(token);
        } catch (RuntimeException e) {
            synchronized (this) {
                if (generation == started && e instanceof YouTubeException failure
                        && failure.kind() == ContentSourceException.Kind.REVOKED) {
                    revoked = failure;
                }
                finished(refresh);
            }
            refresh.completeExceptionally(e);
        } finally {
            if (!refresh.isDone()) {
                // An Error: the callers waiting for this refresh must not wait forever.
                synchronized (this) {
                    finished(refresh);
                }
                refresh.cancel(false);
            }
        }
    }

    /** Under the monitor: the next caller starts a refresh of its own, unless a newer one is already under way. */
    private void finished(CompletableFuture<GoogleOAuthClient.AccessToken> refresh) {
        if (refreshing == refresh) {
            refreshing = null;
        }
    }

    private static GoogleOAuthClient.AccessToken await(CompletableFuture<GoogleOAuthClient.AccessToken> refresh) {
        try {
            return refresh.get();
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            throw new YouTubeException(ContentSourceException.Kind.UNREACHABLE, "Stopped waiting for Google");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException failure) {
                throw failure;
            }
            throw new IllegalStateException(e.getCause());
        }
    }

    public synchronized void prime(GoogleOAuthClient.AccessToken token) {
        generation++;
        refreshing = null;
        current = token;
    }

    public synchronized void invalidate() {
        generation++;
        refreshing = null;
        current = null;
    }

    public synchronized void reset() {
        generation++;
        refreshing = null;
        current = null;
        revoked = null;
    }

    /** Read without the monitor, so the setup page never waits for a refresh. */
    public boolean revoked() {
        return revoked != null;
    }

    public boolean hasClient() {
        return secrets.secret(YouTubeSettings.CLIENT_ID).isPresent() && secrets.secret(YouTubeSettings.CLIENT_SECRET).isPresent();
    }

    public boolean hasRefreshToken() {
        return secrets.secret(YouTubeSettings.REFRESH_TOKEN).isPresent();
    }
}

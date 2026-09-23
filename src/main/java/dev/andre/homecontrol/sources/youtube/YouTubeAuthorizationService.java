package dev.andre.homecontrol.sources.youtube;

import dev.andre.homecontrol.storage.JsonFileSourceSettings;
import dev.andre.homecontrol.storage.SecretStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** One pending authorization at a time; device codes and browser PKCE verifiers stay in memory. */
public class YouTubeAuthorizationService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(YouTubeAuthorizationService.class);
    private static final Duration SLOW_DOWN_STEP = Duration.ofSeconds(5);

    public enum State { IDLE, PENDING, BROWSER_PENDING, CONNECTED, DENIED, EXPIRED, FAILED }

    private record BrowserRequest(String sessionId, String state, String verifier, URI redirectUri, Instant expiresAt) {
        @Override public String toString() { return "BrowserRequest[expiresAt=" + expiresAt + "]"; }
    }

    public record Status(State state, String userCode, URI verificationUrl, Instant expiresAt, String message) {
        static Status of(State state, String message) {
            return new Status(state, null, null, null, message);
        }
    }

    private final GoogleOAuthClient oauth;
    private final SecretStore secrets;
    private final GoogleTokens tokens;
    private final JsonFileSourceSettings settings;
    private final Clock clock;
    private final List<Runnable> connectedListeners = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService scheduler;

    private GoogleOAuthClient.DeviceCode pending;
    private BrowserRequest browser;
    private final SecureRandom random = new SecureRandom();
    private Duration interval;
    private Instant nextPollAt;
    private Status status = Status.of(State.IDLE, null);

    public YouTubeAuthorizationService(GoogleOAuthClient oauth, SecretStore secrets, GoogleTokens tokens,
                                       JsonFileSourceSettings settings, Clock clock, boolean backgroundPolling) {
        this.oauth = oauth;
        this.secrets = secrets;
        this.tokens = tokens;
        this.settings = settings;
        this.clock = clock;
        if (backgroundPolling) {
            scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform()
                    .name("youtube-authorization").daemon(true).factory());
            scheduler.scheduleWithFixedDelay(this::tick, 1, 1, TimeUnit.SECONDS);
        } else {
            scheduler = null;
        }
    }

    public void onConnected(Runnable listener) {
        connectedListeners.add(listener);
    }

    public synchronized Status start() {
        String clientId = secrets.secret(YouTubeSettings.CLIENT_ID).orElse(null);
        if (clientId == null || secrets.secret(YouTubeSettings.CLIENT_SECRET).isEmpty()) {
            throw new YouTubeException(YouTubeException.Kind.NOT_CONFIGURED, "Save the OAuth client ID and secret first");
        }
        GoogleOAuthClient.DeviceCode code = oauth.requestDeviceCode(clientId);
        synchronized (this) {
            browser = null;
            pending = code;
            interval = code.interval();
            nextPollAt = clock.instant().plus(interval);
            status = new Status(State.PENDING, code.userCode(), code.verificationUrl(), code.expiresAt(), null);
            return status;
        }
    }

    public synchronized Status status() {
        if (browser != null && !clock.instant().isBefore(browser.expiresAt())) {
            finish(State.EXPIRED, "Sign-in expired. Start again.");
        }
        return status;
    }

    public synchronized URI startBrowser(URI redirectUri, String sessionId) {
        String clientId = secrets.secret(YouTubeSettings.CLIENT_ID).orElse(null);
        if (clientId == null || secrets.secret(YouTubeSettings.CLIENT_SECRET).isEmpty()) {
            throw new YouTubeException(YouTubeException.Kind.NOT_CONFIGURED, "Save the OAuth client ID and secret first");
        }
        String state = randomToken();
        String verifier = randomToken();
        browser = new BrowserRequest(sessionId, state, verifier, redirectUri, clock.instant().plusSeconds(600));
        pending = null;
        status = Status.of(State.BROWSER_PENDING, "Complete sign-in on Google, or start again below.");
        try {
            String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
            return oauth.authorizationUrl(clientId, redirectUri, state, challenge);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public synchronized void completeBrowser(String sessionId, String state, String code, String error) {
        BrowserRequest request = browser;
        if (request == null || sessionId == null || !request.sessionId().equals(sessionId) || state == null
                || !MessageDigest.isEqual(request.state().getBytes(StandardCharsets.UTF_8), state.getBytes(StandardCharsets.UTF_8))) {
            throw new YouTubeException(YouTubeException.Kind.INVALID_INPUT,
                    "This sign-in request is no longer valid. Start again from Setup in the same browser.");
        }
        browser = null; // single use, including failed exchanges and denied consent
        if (!clock.instant().isBefore(request.expiresAt())) {
            finish(State.EXPIRED, "Sign-in expired. Start again.");
        } else if (error != null) {
            finish("access_denied".equals(error) ? State.DENIED : State.FAILED,
                    "access_denied".equals(error) ? "Access was denied on the Google page. Start again to retry."
                            : "Google could not complete sign-in. Check your OAuth client settings and try again.");
        } else if (code == null || code.isBlank() || code.length() > 4096) {
            finish(State.FAILED, "Google did not return an authorization code. Start again.");
        } else {
            try {
                storeGrant(oauth.exchangeCode(secrets.secret(YouTubeSettings.CLIENT_ID).orElseThrow(),
                        secrets.secret(YouTubeSettings.CLIENT_SECRET).orElseThrow(), code, request.redirectUri(), request.verifier()));
            } catch (YouTubeException e) {
                finish(State.FAILED, e.getMessage());
            }
        }
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public synchronized void cancel() {
        pending = null;
        browser = null;
        status = Status.of(State.IDLE, null);
    }

    /** One poll if one is due. Returns true while the authorization is still pending. */
    public boolean pollOnce() {
        GoogleOAuthClient.DeviceCode code;
        synchronized (this) {
            if (pending == null) {
                return false;
            }
            Instant now = clock.instant();
            if (!now.isBefore(pending.expiresAt())) {
                return finish(State.EXPIRED, "The code expired before it was entered. Start again.");
            }
            if (now.isBefore(nextPollAt)) {
                return true;
            }
            code = pending;
        }
        String clientId = secrets.secret(YouTubeSettings.CLIENT_ID).orElse("");
        String clientSecret = secrets.secret(YouTubeSettings.CLIENT_SECRET).orElse("");
        GoogleOAuthClient.TokenPoll result;
        try {
            result = oauth.poll(clientId, clientSecret, code.deviceCode());
        } catch (YouTubeException e) {
            synchronized (this) {
                if (pending != code) {
                    return pending != null;
                }
                if (e.kind() == YouTubeException.Kind.UNREACHABLE || e.kind() == YouTubeException.Kind.SERVER_ERROR) {
                    nextPollAt = clock.instant().plus(interval);
                    status = new Status(State.PENDING, code.userCode(), code.verificationUrl(), code.expiresAt(),
                            "Could not reach Google; still trying");
                    return true;
                }
                return finish(State.FAILED, e.getMessage());
            }
        }
        synchronized (this) {
            if (pending != code) {
                return pending != null; // cancelled or restarted meanwhile
            }
            switch (result) {
                case GoogleOAuthClient.TokenPoll.Pending _ -> {
                    nextPollAt = clock.instant().plus(interval);
                    return true;
                }
                case GoogleOAuthClient.TokenPoll.SlowDown _ -> {
                    interval = interval.plus(SLOW_DOWN_STEP);
                    nextPollAt = clock.instant().plus(interval);
                    return true;
                }
                case GoogleOAuthClient.TokenPoll.Denied _ -> {
                    return finish(State.DENIED, "Access was denied on the Google page. Start again to retry.");
                }
                case GoogleOAuthClient.TokenPoll.Expired _ -> {
                    return finish(State.EXPIRED, "The code expired before it was entered. Start again.");
                }
                case GoogleOAuthClient.TokenPoll.Failed failed -> {
                    return finish(State.FAILED, "Google refused the authorization (" + failed.error()
                            + (failed.description().isBlank() ? "" : ": " + failed.description()) + ")");
                }
                case GoogleOAuthClient.TokenPoll.Granted granted -> {
                    storeGrant(granted);
                }
            }
        }
        return false;
    }

    private void storeGrant(GoogleOAuthClient.TokenPoll.Granted granted) {
        secrets.putSecrets(Map.of(YouTubeSettings.REFRESH_TOKEN, granted.refreshToken()));
        tokens.reset();
        tokens.prime(granted.accessToken());
        YouTubeSettings current = YouTubeSettings.from(settings.get(YouTubeSettings.SOURCE_ID));
        settings.put(YouTubeSettings.SOURCE_ID,
                current.withoutAccount().withConnection(clock.instant(), null, null).toMap());
        finish(State.CONNECTED, "YouTube connected");
        notifyConnected();
        // Restore rail choices only after Google confirms this is still the same channel.
        // Failed channel lookups leave an empty library instead of showing another account's data.
        YouTubeSettings resolved = YouTubeSettings.from(settings.get(YouTubeSettings.SOURCE_ID));
        if (current.channelId() != null && current.channelId().equals(resolved.channelId())) {
            settings.put(YouTubeSettings.SOURCE_ID,
                    resolved.withPlaylists(current.playlists()).withWatchLater(current.watchLater()).toMap());
        }
    }

    private void notifyConnected() {
        for (Runnable listener : connectedListeners) {
            try {
                listener.run();
            } catch (RuntimeException e) {
                log.warn("YouTube post-connect step failed: {}", e.getMessage());
            }
        }
    }

    private boolean finish(State state, String message) {
        pending = null;
        browser = null;
        status = Status.of(state, message);
        return false;
    }

    private void tick() {
        try {
            pollOnce();
        } catch (RuntimeException e) {
            log.warn("YouTube authorization poll failed: {}", e.getMessage());
        }
    }

    @Override
    public void close() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }
}

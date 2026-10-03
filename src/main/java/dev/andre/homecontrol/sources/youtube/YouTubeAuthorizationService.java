package dev.andre.homecontrol.sources.youtube;

import dev.andre.homecontrol.core.content.ContentSourceException;
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
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * One pending authorization at a time; device codes and browser PKCE verifiers stay in memory. The monitor guards that
 * state alone: calls to Google and the connect hook run outside it, so {@link #status()}, which every setup render
 * asks, never waits for them. A grant is kept only while no start, sign-in or cancel came after the request it
 * answers ({@code generation}).
 */
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
    private long generation;

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

    public Status start() {
        String clientId = secrets.secret(YouTubeSettings.CLIENT_ID).orElse(null);
        if (clientId == null || secrets.secret(YouTubeSettings.CLIENT_SECRET).isEmpty()) {
            throw new YouTubeException(ContentSourceException.Kind.NOT_CONFIGURED, "Save the OAuth client ID and secret first");
        }
        long requested;
        synchronized (this) {
            requested = generation;
        }
        GoogleOAuthClient.DeviceCode code = oauth.requestDeviceCode(clientId);
        synchronized (this) {
            if (generation != requested) {
                return status; // cancelled, or another sign-in started, while Google answered
            }
            generation++;
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
            throw new YouTubeException(ContentSourceException.Kind.NOT_CONFIGURED, "Save the OAuth client ID and secret first");
        }
        String state = randomToken();
        String verifier = randomToken();
        generation++;
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

    public void completeBrowser(String sessionId, String state, String code, String error) {
        BrowserRequest request;
        long current;
        String clientId;
        String clientSecret;
        synchronized (this) {
            request = browser;
            if (request == null || sessionId == null || !request.sessionId().equals(sessionId) || state == null
                    || !MessageDigest.isEqual(request.state().getBytes(StandardCharsets.UTF_8), state.getBytes(StandardCharsets.UTF_8))) {
                throw new YouTubeException(ContentSourceException.Kind.INVALID_INPUT,
                        "This sign-in request is no longer valid. Start again from Setup in the same browser.");
            }
            browser = null; // single use, including failed exchanges and denied consent
            if (!clock.instant().isBefore(request.expiresAt())) {
                finish(State.EXPIRED, "Sign-in expired. Start again.");
                return;
            }
            if (error != null) {
                finish("access_denied".equals(error) ? State.DENIED : State.FAILED,
                        "access_denied".equals(error) ? "Access was denied on the Google page. Start again to retry."
                                : "Google could not complete sign-in. Check your OAuth client settings and try again.");
                return;
            }
            if (code == null || code.isBlank() || code.length() > 4096) {
                finish(State.FAILED, "Google did not return an authorization code. Start again.");
                return;
            }
            current = generation;
            clientId = secrets.secret(YouTubeSettings.CLIENT_ID).orElseThrow();
            clientSecret = secrets.secret(YouTubeSettings.CLIENT_SECRET).orElseThrow();
        }
        GoogleOAuthClient.TokenPoll.Granted granted;
        try {
            granted = oauth.exchangeCode(clientId, clientSecret, code, request.redirectUri(), request.verifier());
        } catch (YouTubeException e) {
            synchronized (this) {
                if (generation == current) {
                    finish(State.FAILED, e.getMessage());
                }
            }
            return;
        }
        storeGrant(current, granted.accessToken(), granted.refreshToken());
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public synchronized void cancel() {
        generation++;
        pending = null;
        browser = null;
        status = Status.of(State.IDLE, null);
    }

    /**
     * Trouble on the way to Google, or all of Home Control's connections to it in use: the code stays valid, so the
     * next poll may well succeed. The status line for that, or empty when the failure ends the authorization.
     */
    private static Optional<String> stillTrying(ContentSourceException.Kind kind) {
        return switch (kind) {
            case UNREACHABLE, SERVER_ERROR -> Optional.of("Could not reach Google; still trying");
            case RATE_LIMITED -> Optional.of("Home Control is busy talking to Google; still trying");
            default -> Optional.empty();
        };
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
                Optional<String> stillTrying = stillTrying(e.kind());
                if (stillTrying.isPresent()) {
                    nextPollAt = clock.instant().plus(interval);
                    status = new Status(State.PENDING, code.userCode(), code.verificationUrl(), code.expiresAt(),
                            stillTrying.get());
                    return true;
                }
                return finish(State.FAILED, e.getMessage());
            }
        }
        GoogleOAuthClient.TokenPoll.Granted granted;
        long current;
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
                case GoogleOAuthClient.TokenPoll.Failed(var error, var description) -> {
                    return finish(State.FAILED, "Google refused the authorization (" + error
                            + (description.isBlank() ? "" : ": " + description) + ")");
                }
                case GoogleOAuthClient.TokenPoll.Granted grant -> {
                    pending = null; // used up: no later tick polls it again
                    granted = grant;
                    current = generation;
                }
            }
        }
        storeGrant(current, granted.accessToken(), granted.refreshToken());
        return false;
    }

    /** Keeps the grant unless {@code expected} is no longer the current generation; the hook runs outside the monitor. */
    private void storeGrant(long expected, GoogleOAuthClient.AccessToken accessToken, String refreshToken) {
        YouTubeSettings previous;
        synchronized (this) {
            if (generation != expected) {
                return; // cancelled, restarted or disconnected while Google answered: the grant is not kept
            }
            secrets.putSecrets(Map.of(YouTubeSettings.REFRESH_TOKEN, refreshToken));
            tokens.reset();
            tokens.prime(accessToken);
            previous = YouTubeSettings.read(settings);
            settings.put(YouTubeSettings.SOURCE_ID,
                    previous.withoutAccount().withConnection(clock.instant(), null, null));
            finish(State.CONNECTED, "YouTube connected");
        }
        notifyConnected();
        // Restore rail choices only after Google confirms this is still the same channel.
        // Failed channel lookups leave an empty library instead of showing another account's data.
        synchronized (this) {
            YouTubeSettings resolved = YouTubeSettings.read(settings);
            if (generation == expected && previous.channelId() != null
                    && previous.channelId().equals(resolved.channelId())) {
                settings.put(YouTubeSettings.SOURCE_ID,
                        resolved.withPlaylists(previous.playlists()).withWatchLater(previous.watchLater()));
            }
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

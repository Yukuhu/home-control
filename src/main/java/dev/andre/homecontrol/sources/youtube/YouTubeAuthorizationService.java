package dev.andre.homecontrol.sources.youtube;

import dev.andre.homecontrol.storage.JsonFileSourceSettings;
import dev.andre.homecontrol.storage.SecretStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** One pending device authorization at a time; polled in the background; the device code never leaves here. */
public class YouTubeAuthorizationService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(YouTubeAuthorizationService.class);
    private static final Duration SLOW_DOWN_STEP = Duration.ofSeconds(5);

    public enum State { IDLE, PENDING, CONNECTED, DENIED, EXPIRED, FAILED }

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

    public Status start() {
        String clientId = secrets.secret(YouTubeSettings.CLIENT_ID).orElse(null);
        if (clientId == null || secrets.secret(YouTubeSettings.CLIENT_SECRET).isEmpty()) {
            throw new YouTubeException(YouTubeException.Kind.NOT_CONFIGURED, "Save the OAuth client ID and secret first");
        }
        GoogleOAuthClient.DeviceCode code = oauth.requestDeviceCode(clientId);
        synchronized (this) {
            pending = code;
            interval = code.interval();
            nextPollAt = clock.instant().plus(interval);
            status = new Status(State.PENDING, code.userCode(), code.verificationUrl(), code.expiresAt(), null);
            return status;
        }
    }

    public synchronized Status status() {
        return status;
    }

    public synchronized void cancel() {
        pending = null;
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
                case GoogleOAuthClient.TokenPoll.Pending ignored -> {
                    nextPollAt = clock.instant().plus(interval);
                    return true;
                }
                case GoogleOAuthClient.TokenPoll.SlowDown ignored -> {
                    interval = interval.plus(SLOW_DOWN_STEP);
                    nextPollAt = clock.instant().plus(interval);
                    return true;
                }
                case GoogleOAuthClient.TokenPoll.Denied ignored -> {
                    return finish(State.DENIED, "Access was denied on the Google page. Start again to retry.");
                }
                case GoogleOAuthClient.TokenPoll.Expired ignored -> {
                    return finish(State.EXPIRED, "The code expired before it was entered. Start again.");
                }
                case GoogleOAuthClient.TokenPoll.Failed failed -> {
                    return finish(State.FAILED, "Google refused the authorization (" + failed.error()
                            + (failed.description().isBlank() ? "" : ": " + failed.description()) + ")");
                }
                case GoogleOAuthClient.TokenPoll.Granted granted -> {
                    secrets.putSecrets(Map.of(YouTubeSettings.REFRESH_TOKEN, granted.refreshToken()));
                    tokens.reset();
                    tokens.prime(granted.accessToken());
                    YouTubeSettings current = YouTubeSettings.from(settings.get(YouTubeSettings.SOURCE_ID));
                    settings.put(YouTubeSettings.SOURCE_ID,
                            current.withConnection(clock.instant(), current.channelId(), current.channelTitle()).toMap());
                    finish(State.CONNECTED, "YouTube connected");
                }
            }
        }
        for (Runnable listener : connectedListeners) {
            try {
                listener.run();
            } catch (RuntimeException e) {
                log.warn("YouTube post-connect step failed: {}", e.getMessage());
            }
        }
        return false;
    }

    private boolean finish(State state, String message) {
        pending = null;
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

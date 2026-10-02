package dev.andre.homecontrol.adapters.androidtv;

import dev.andre.homecontrol.core.LaunchedMedia;
import dev.andre.homecontrol.core.NowPlaying;
import dev.andre.homecontrol.core.PlaybackState;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Android TV Remote v2 reports the foreground app and nothing about media, so what plays is
 * inferred from what this server launched: the media counts as playing while its app is in
 * front. Pause, seek and the position cannot be seen, so none is reported. Not thread-safe;
 * the session confines it to its scheduler.
 */
final class InferredPlayback {

    /** How long the launched app may take to come to the front before the launch counts as failed. */
    static final Duration APP_GRACE = Duration.ofSeconds(30);
    /** Allowance for pauses, on top of the runtime, before media whose app stayed in front is dropped. */
    static final Duration EXPIRY_MARGIN = Duration.ofMinutes(30);

    private final Duration appGrace;
    private final Duration expiryMargin;
    private LaunchedMedia media;
    private Instant launchedAt;
    private boolean appSeen;

    InferredPlayback() {
        this(APP_GRACE, EXPIRY_MARGIN);
    }

    InferredPlayback(Duration appGrace, Duration expiryMargin) {
        this.appGrace = appGrace;
        this.expiryMargin = expiryMargin;
    }

    void launched(LaunchedMedia launched, String currentApp, Instant now) {
        media = launched;
        launchedAt = now;
        appSeen = launched.appPackage().equals(currentApp);
    }

    void appChanged(String appPackage) {
        if (media == null) {
            return;
        }
        if (media.appPackage().equals(appPackage)) {
            appSeen = true;
        } else if (appSeen) {
            media = null;
        }
        // Until the launched app has been seen, the app it replaces may still be reported.
    }

    void poweredOff() {
        media = null;
    }

    /** What plays at {@code now}, or null. */
    NowPlaying current(Instant now) {
        if (nextDeadline().filter(deadline -> !now.isBefore(deadline)).isPresent()) {
            media = null;
        }
        if (media == null || !appSeen) {
            return null;
        }
        return new NowPlaying(media.title(), PlaybackState.PLAYING, null, media.durationSeconds());
    }

    /** When {@link #current} next changes by itself, if it will. */
    Optional<Instant> nextDeadline() {
        if (media == null) {
            return Optional.empty();
        }
        if (!appSeen) {
            return Optional.of(launchedAt.plus(appGrace));
        }
        if (media.durationSeconds() == null) {
            return Optional.empty();
        }
        return Optional.of(launchedAt.plusMillis(Math.round(media.durationSeconds() * 1000)).plus(expiryMargin));
    }
}

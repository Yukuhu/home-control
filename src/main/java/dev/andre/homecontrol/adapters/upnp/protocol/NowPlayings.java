package dev.andre.homecontrol.adapters.upnp.protocol;

import dev.andre.homecontrol.core.NowPlaying;
import dev.andre.homecontrol.core.PlaybackState;

import java.util.Optional;

/** AVTransport state and position, as the rest of the system sees what plays (spec §6.2). */
public final class NowPlayings {

    private NowPlayings() {
    }

    /** Null when nothing is loaded and playing, paused or buffering. */
    public static NowPlaying of(TransportInfo transport, PositionInfo position, PlayedItem lastPlayed) {
        PlaybackState state = switch (transport.state()) {
            case "PLAYING" -> PlaybackState.PLAYING;
            case "PAUSED_PLAYBACK", "PAUSED_RECORDING" -> PlaybackState.PAUSED;
            case "TRANSITIONING" -> PlaybackState.BUFFERING;
            default -> null;
        };
        if (state == null) {
            return null;
        }
        String title = DidlLite.title(position.trackMetadata())
                .or(() -> Optional.ofNullable(lastPlayed)
                        .filter(played -> played.uri().equals(position.trackUri()))
                        .map(PlayedItem::title)
                        .filter(played -> !played.isBlank()))
                .orElse("Unknown title");
        return new NowPlaying(title, state, position.positionSeconds() == null ? 0.0 : position.positionSeconds(),
                position.durationSeconds());
    }
}

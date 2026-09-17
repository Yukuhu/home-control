package dev.andre.homecontrol.core.playback;

import dev.andre.homecontrol.core.Capability;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** Rung 5 of spec §5.3, the last: a local audio sink plays an http(s) audio stream through the server's player. */
public class LocalAudioSinkStrategy implements RouteStrategy {

    @Override
    public Optional<Route> route(ContentItem item, Set<Capability> capabilities) {
        if (!capabilities.contains(Capability.LOCAL_AUDIO_SINK)) {
            return Optional.empty();
        }
        return item.playables().stream()
                .filter(PlayableRef.StreamUrl.class::isInstance)
                .map(PlayableRef.StreamUrl.class::cast)
                .filter(LocalAudioSinkStrategy::playable)
                .findFirst()
                .map(stream -> new Route.PlayLocally(stream.url(), stream.mimeType(), item.title(), item.subtitle()));
    }

    /** Only audio, and only over HTTP: the server must never open local files or decode video for a speaker. */
    public static boolean playable(PlayableRef.StreamUrl stream) {
        String scheme = stream.url().getScheme();
        return scheme != null
                && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                && stream.mimeType() != null
                && stream.mimeType().toLowerCase(Locale.ROOT).startsWith("audio/");
    }
}

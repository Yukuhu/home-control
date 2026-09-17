package dev.andre.homecontrol.core.playback;

import dev.andre.homecontrol.core.Action;
import dev.andre.homecontrol.core.Capability;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LocalAudioSinkStrategyTest {

    private final LocalAudioSinkStrategy strategy = new LocalAudioSinkStrategy();

    private static ContentItem song(PlayableRef... playables) {
        return new ContentItem("x", "test", ContentKind.TRACK, "Bunny Song", "The Rabbits", null, List.of(playables));
    }

    @Test
    void playsTheFirstAudioStream() {
        ContentItem item = song(new PlayableRef.StreamUrl(URI.create("http://nas/film.mp4"), "video/mp4"),
                new PlayableRef.StreamUrl(URI.create("http://nas/a.flac"), "audio/flac"),
                new PlayableRef.StreamUrl(URI.create("http://nas/b.mp3"), "audio/mpeg"));

        Route route = strategy.route(item, EnumSet.of(Capability.LOCAL_AUDIO_SINK, Capability.VOLUME)).orElseThrow();

        assertThat(route).isEqualTo(new Route.PlayLocally(URI.create("http://nas/a.flac"), "audio/flac", "Bunny Song", "The Rabbits"));
        assertThat(((Route.PlayLocally) route).action())
                .isEqualTo(new Action.PlayMedia(URI.create("http://nas/a.flac"), "audio/flac", "Bunny Song", "The Rabbits"));
        assertThat(route.describe()).isEqualTo("Play through the server on this Bluetooth speaker");
    }

    @Test
    void needsALocalAudioSink() {
        PlayableRef.StreamUrl stream = new PlayableRef.StreamUrl(URI.create("http://nas/a.flac"), "audio/flac");
        assertThat(strategy.route(song(stream), EnumSet.of(Capability.MEDIA_RENDERER, Capability.VOLUME))).isEmpty();
    }

    @Test
    void refusesVideoAndNonHttpStreams() {
        PlayableRef.StreamUrl video = new PlayableRef.StreamUrl(URI.create("http://nas/film.mp4"), "video/mp4");
        assertThat(strategy.route(song(video), EnumSet.of(Capability.LOCAL_AUDIO_SINK))).isEmpty();
        assertThat(LocalAudioSinkStrategy.playable(video)).isFalse();

        PlayableRef.StreamUrl file = new PlayableRef.StreamUrl(URI.create("file:///music/a.flac"), "audio/flac");
        assertThat(strategy.route(song(file), EnumSet.of(Capability.LOCAL_AUDIO_SINK))).isEmpty();
        assertThat(LocalAudioSinkStrategy.playable(file)).isFalse();

        PlayableRef.StreamUrl https = new PlayableRef.StreamUrl(URI.create("HTTPS://nas/a.ogg"), "Audio/Ogg");
        assertThat(strategy.route(song(https), EnumSet.of(Capability.LOCAL_AUDIO_SINK))).isPresent();
        assertThat(LocalAudioSinkStrategy.playable(https)).isTrue();
    }

    @Test
    void neverPrintsTheStreamCredential() {
        String printed = new Route.PlayLocally(URI.create("http://h:8096/Audio/x/stream.flac?ApiKey=secret-key"), "audio/flac", "T", null)
                .toString();

        assertThat(printed).contains("http://h:8096/Audio/x/stream.flac?…").doesNotContain("secret-key");
    }
}

package dev.andre.homecontrol.core.playback;

import dev.andre.homecontrol.core.Action;
import dev.andre.homecontrol.core.Capability;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MediaRendererStrategyTest {

    private final MediaRendererStrategy strategy = new MediaRendererStrategy();

    private static ContentItem song(PlayableRef... playables) {
        return new ContentItem("x", "test", ContentKind.TRACK, "Bunny Song", "The Rabbits", null, List.of(playables));
    }

    @Test
    void rendersTheFirstStreamWithTheItemsTitleAndSubtitle() {
        ContentItem item = song(new PlayableRef.AppLink(URI.create("https://music.example/x"), "web"),
                new PlayableRef.StreamUrl(URI.create("http://nas/a.flac"), "audio/flac"),
                new PlayableRef.StreamUrl(URI.create("http://nas/b.mp3"), "audio/mpeg"));

        Route route = strategy.route(item, EnumSet.of(Capability.MEDIA_RENDERER)).orElseThrow();

        assertThat(route).isEqualTo(new Route.Render(URI.create("http://nas/a.flac"), "audio/flac", "Bunny Song", "The Rabbits"));
        assertThat(((Route.Render) route).action())
                .isEqualTo(new Action.PlayMedia(URI.create("http://nas/a.flac"), "audio/flac", "Bunny Song", "The Rabbits"));
        assertThat(route.describe()).isEqualTo("Stream directly to this device (DLNA/UPnP)");
    }

    @Test
    void needsAMediaRenderer() {
        PlayableRef.StreamUrl stream = new PlayableRef.StreamUrl(URI.create("http://nas/a.flac"), "audio/flac");

        assertThat(strategy.route(song(stream), EnumSet.of(Capability.CAST_RECEIVER, Capability.VOLUME))).isEmpty();
        assertThat(strategy.route(song(new PlayableRef.AppLink(URI.create("https://x"), "web")),
                EnumSet.of(Capability.MEDIA_RENDERER))).isEmpty();
    }

    @Test
    void neverPrintsTheStreamCredential() {
        String printed = new Route.Render(URI.create("http://h:8096/Audio/x/stream.flac?ApiKey=secret-key"), "audio/flac", "T", null)
                .toString();

        assertThat(printed).contains("http://h:8096/Audio/x/stream.flac?…").doesNotContain("secret-key");
        assertThat(RouteKeys.key(new Route.Render(URI.create("http://h/a?ApiKey=secret-key"), "audio/flac", "T", null)))
                .isEqualTo("render");
    }
}

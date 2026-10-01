package dev.andre.homecontrol.core.playback;

import dev.andre.homecontrol.core.Capability;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RefStrategyTest {

    private static final PlayableRef.StreamUrl VIDEO = new PlayableRef.StreamUrl(URI.create("http://nas.local/a.mp4"), "video/mp4");
    private static final PlayableRef.StreamUrl AUDIO = new PlayableRef.StreamUrl(URI.create("http://nas.local/b.flac"), "audio/flac");
    private static final PlayableRef.AppLink LINK =
            new PlayableRef.AppLink(URI.create("https://www.youtube.com/watch?v=abc"), "youtube");
    private static final Set<Capability> RENDERER = Set.of(Capability.MEDIA_RENDERER);

    private final RefStrategy<PlayableRef.StreamUrl> anyStream = RefStrategy.of(Rung.RENDERER, Capability.MEDIA_RENDERER,
            PlayableRef.StreamUrl.class, RefStrategyTest::render);
    private final RefStrategy<PlayableRef.StreamUrl> audioOnly = new RefStrategy<>(Rung.RENDERER, Capability.MEDIA_RENDERER,
            PlayableRef.StreamUrl.class, stream -> stream.mimeType().startsWith("audio/"), RefStrategyTest::render);

    private static Route render(PlayableRef.StreamUrl stream, ContentItem item) {
        return new Route.Render(stream.url(), stream.mimeType(), item.title(), item.subtitle());
    }

    private static ContentItem item(PlayableRef... playables) {
        return new ContentItem("x", "test", ContentKind.VIDEO, "Title", "Subtitle", null, List.of(playables));
    }

    @Test
    void withoutItsCapabilityItRoutesNothing() {
        assertThat(anyStream.route(item(VIDEO), Set.of(Capability.CAST_RECEIVER, Capability.LOCAL_AUDIO_SINK))).isEmpty();
    }

    @Test
    void itRoutesTheFirstReferenceOfItsType() {
        assertThat(anyStream.route(item(LINK, VIDEO, AUDIO), RENDERER))
                .contains(new Route.Render(VIDEO.url(), "video/mp4", "Title", "Subtitle"));
    }

    @Test
    void itSkipsReferencesItsFilterRefuses() {
        assertThat(audioOnly.route(item(VIDEO, AUDIO), RENDERER))
                .contains(new Route.Render(AUDIO.url(), "audio/flac", "Title", "Subtitle"));
        assertThat(audioOnly.route(item(VIDEO), RENDERER)).isEmpty();
    }

    @Test
    void itIgnoresOtherTypes() {
        assertThat(anyStream.route(item(LINK), RENDERER)).isEmpty();
    }

    @Test
    void itStandsOnItsRung() {
        assertThat(anyStream.rung()).isEqualTo(Rung.RENDERER);
    }
}

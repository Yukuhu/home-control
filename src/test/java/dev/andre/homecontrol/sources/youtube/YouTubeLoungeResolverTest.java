package dev.andre.homecontrol.sources.youtube;

import dev.andre.homecontrol.core.Capability;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.core.playback.ContentItem;
import dev.andre.homecontrol.core.playback.ContentKind;
import dev.andre.homecontrol.core.playback.PlayableRef;
import dev.andre.homecontrol.core.playback.PlayableResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class YouTubeLoungeResolverTest {

    private static final PlayableRef.AppLink LINK =
            new PlayableRef.AppLink(URI.create("https://www.youtube.com/watch?v=aqz-KE-bpKQ"), "youtube");
    private static final ContentItem ITEM = new ContentItem("aqz-KE-bpKQ", "youtube", ContentKind.VIDEO, "Big Buck Bunny",
            null, null, List.of(LINK));

    private final YouTubeSetupService setup = mock(YouTubeSetupService.class);
    private final YouTubeLoungeResolver resolver = new YouTubeLoungeResolver(setup);

    @BeforeEach
    void kitchenIsSwitchedOn() {
        given(setup.settings()).willReturn(YouTubeSettings.EMPTY.withLoungeDevice("kitchen", true));
    }

    private static Device device(String id) {
        return new Device(id, id, DeviceKind.CAST, "10.0.0.9", Map.of("cast", Map.of()), Instant.now());
    }

    @Test
    void resolvesOnlyYouTubeVideoLinks() {
        assertThat(resolver.resolves(LINK)).isTrue();
        assertThat(resolver.resolves(new PlayableRef.AppLink(URI.create("https://www.youtube.com/"), "youtube"))).isFalse();
        assertThat(resolver.resolves(new PlayableRef.AppLink(URI.create("https://www.netflix.com/title/80100172"), "netflix")))
                .isFalse();
        assertThat(resolver.resolves(new PlayableRef.StreamUrl(URI.create("http://nas/x.mp4"), "video/mp4"))).isFalse();
    }

    @Test
    void addsLoungeForSwitchedOnCastDevices() {
        PlayableResolver.Resolution resolution = resolver.resolve(LINK, ITEM, device("kitchen"),
                EnumSet.of(Capability.CAST_RECEIVER));

        assertThat(resolution.playables()).containsExactly(LINK, new PlayableRef.YouTubeLounge("aqz-KE-bpKQ"));
        assertThat(resolution.liveCapabilities()).isEmpty();
        assertThat(resolution.notes()).isEmpty();
    }

    @Test
    void leavesOtherDevicesAlone() {
        assertThat(resolver.resolve(LINK, ITEM, device("living"), Set.of(Capability.CAST_RECEIVER, Capability.APP_LINK))
                .playables()).containsExactly(LINK);
        assertThat(resolver.resolve(LINK, ITEM, device("kitchen"), Set.of(Capability.APP_LINK)).playables())
                .containsExactly(LINK);
    }
}

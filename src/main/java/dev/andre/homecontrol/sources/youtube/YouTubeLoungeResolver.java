package dev.andre.homecontrol.sources.youtube;

import dev.andre.homecontrol.core.Capability;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.playback.ContentItem;
import dev.andre.homecontrol.core.playback.PlayableRef;
import dev.andre.homecontrol.core.playback.PlayableResolver;

import java.util.List;
import java.util.Set;

/**
 * Adds the best-effort Lounge reference next to a YouTube video link, only for Cast devices whose
 * YouTube Cast switch is on (off by default). The app link stays first, so devices that can open it still do.
 */
public class YouTubeLoungeResolver implements PlayableResolver {

    private final YouTubeSetupService setup;

    public YouTubeLoungeResolver(YouTubeSetupService setup) {
        this.setup = setup;
    }

    @Override
    public boolean resolves(PlayableRef ref) {
        return ref instanceof PlayableRef.AppLink(var uri, var service) && "youtube".equals(service)
                && YouTubeVideoIds.fromUrl(uri).isPresent();
    }

    @Override
    public Resolution resolve(PlayableRef ref, ContentItem item, Device device, Set<Capability> capabilities) {
        PlayableRef.AppLink link = (PlayableRef.AppLink) ref;
        if (!capabilities.contains(Capability.CAST_RECEIVER) || !setup.settings().loungeDevices().contains(device.id())) {
            return new Resolution(List.of(link), Set.of(), List.of());
        }
        String videoId = YouTubeVideoIds.fromUrl(link.uri()).orElseThrow();
        return new Resolution(List.of(link, new PlayableRef.YouTubeLounge(videoId)), Set.of(), List.of());
    }
}

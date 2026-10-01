package dev.andre.homecontrol.core.playback;

import dev.andre.homecontrol.core.Capability;

import java.util.List;
import java.util.Locale;

/** Core's route strategies, one per shared reference on its rung of spec §5.3. Sources declare their own. */
public final class RouteStrategies {

    private RouteStrategies() {
    }

    /** All six, in ladder order. */
    public static List<RouteStrategy> core() {
        return List.of(appLink(), castMessage(), castLoad(), castStream(), renderer(), localSink());
    }

    /** The device can open app links and the item has one. */
    public static RouteStrategy appLink() {
        return RefStrategy.of(Rung.APP_LINK, Capability.APP_LINK, PlayableRef.AppLink.class,
                (link, item) -> new Route.OpenAppLink(link.uri(), link.service()));
    }

    /** A receiver app driven by custom messages (Jellyfin's), before LOADs and bare streams. */
    public static RouteStrategy castMessage() {
        return RefStrategy.of(Rung.CAST_MESSAGE, Capability.CAST_RECEIVER, PlayableRef.CastMessage.class,
                (message, item) -> new Route.CastMessage(message.message(), message.receiverLabel()));
    }

    /** A ready-made LOAD for a receiver app (Jellyfin's, the Default Media Receiver, …). */
    public static RouteStrategy castLoad() {
        return RefStrategy.of(Rung.CAST_LOAD, Capability.CAST_RECEIVER, PlayableRef.CastLoad.class,
                (load, item) -> new Route.Cast(load.receiverAppId(), load.payload()));
    }

    /** A direct stream on a Cast receiver goes through the Default Media Receiver. */
    public static RouteStrategy castStream() {
        return RefStrategy.of(Rung.CAST_STREAM, Capability.CAST_RECEIVER, PlayableRef.StreamUrl.class,
                (stream, item) -> new Route.Cast(CastLoads.DEFAULT_MEDIA_RECEIVER,
                        CastLoads.defaultMediaReceiver(stream, item.title())));
    }

    /** A media renderer (DLNA, Sonos) plays the item's direct stream. */
    public static RouteStrategy renderer() {
        return RefStrategy.of(Rung.RENDERER, Capability.MEDIA_RENDERER, PlayableRef.StreamUrl.class,
                (stream, item) -> new Route.Render(stream.url(), stream.mimeType(), item.title(), item.subtitle()));
    }

    /** A local audio sink plays an http(s) audio stream through the server's player. */
    public static RouteStrategy localSink() {
        return new RefStrategy<>(Rung.LOCAL_SINK, Capability.LOCAL_AUDIO_SINK, PlayableRef.StreamUrl.class,
                RouteStrategies::playsLocally,
                (stream, item) -> new Route.PlayLocally(stream.url(), stream.mimeType(), item.title(), item.subtitle()));
    }

    /** Only audio, and only over HTTP: the server must never open local files or decode video for a speaker. */
    public static boolean playsLocally(PlayableRef.StreamUrl stream) {
        String scheme = stream.url().getScheme();
        return scheme != null
                && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                && stream.mimeType() != null
                && stream.mimeType().toLowerCase(Locale.ROOT).startsWith("audio/");
    }
}

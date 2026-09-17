package dev.andre.homecontrol.adapters.webos;

import dev.andre.homecontrol.adapters.links.ContentLinks;
import dev.andre.homecontrol.core.playback.AppLinks;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.util.Locale;

/**
 * App link → webOS launch (spec §4.1). Deep-link support differs by firmware; the setup page's
 * test button shows whether it took. Payload shapes follow ConnectSDK's WebOSTVService.
 */
final class WebOsLaunches {

    static final String YOUTUBE = "youtube.leanback.v4";
    static final String NETFLIX = "netflix";
    static final String PRIME_VIDEO = "amazon";

    private WebOsLaunches() {
    }

    static WebOsLaunch forUri(URI uri) {
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        return switch (AppLinks.serviceOf(host, uri.getPath())) {
            case "youtube" -> ContentLinks.youtubeVideoId(uri)
                    .map(id -> {
                        String target = "https://www.youtube.com/tv?v=" + id;
                        ObjectNode payload = app(YOUTUBE);
                        payload.put("contentId", target);
                        payload.putObject("params").put("contentTarget", target);
                        return new WebOsLaunch(SsapUris.LAUNCH, payload);
                    })
                    .orElseGet(() -> new WebOsLaunch(SsapUris.LAUNCH, app(YOUTUBE)));
            case "netflix" -> ContentLinks.netflixTitleId(uri)
                    .map(id -> {
                        String contentId = "m=http%3A%2F%2Fapi.netflix.com%2Fcatalog%2Ftitles%2Fmovies%2F"
                                + id + "&source_type=4";
                        ObjectNode payload = app(NETFLIX);
                        payload.put("contentId", contentId);
                        payload.putObject("params").put("contentId", contentId);
                        return new WebOsLaunch(SsapUris.LAUNCH, payload);
                    })
                    .orElseGet(() -> new WebOsLaunch(SsapUris.LAUNCH, app(NETFLIX)));
            case "primevideo" -> new WebOsLaunch(SsapUris.LAUNCH, app(PRIME_VIDEO));
            default -> {
                ObjectNode payload = SsapMessages.empty();
                payload.put("target", uri.toString());
                yield new WebOsLaunch(SsapUris.OPEN, payload);
            }
        };
    }

    private static ObjectNode app(String appId) {
        ObjectNode payload = SsapMessages.empty();
        payload.put("id", appId);
        return payload;
    }
}

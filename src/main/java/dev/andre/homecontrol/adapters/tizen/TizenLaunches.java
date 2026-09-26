package dev.andre.homecontrol.adapters.tizen;

import dev.andre.homecontrol.adapters.links.ContentLinks;
import dev.andre.homecontrol.core.playback.AppLinks;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * App link → Tizen launch (spec §4.1): YouTube videos through DIAL with {@code v=}; Netflix and
 * Prime Video open their app without the title (no content deep link on most firmware); other
 * links cannot be opened. App ids differ by model year, so the installed list wins over the
 * well-known ids.
 */
final class TizenLaunches {

    private static final String YOUTUBE_NAME = "YouTube";

    static final Map<String, String> WELL_KNOWN_IDS = wellKnownIds();

    private TizenLaunches() {
    }

    static TizenLaunch forUri(URI uri, Optional<List<TizenApp>> installed) {
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        return switch (AppLinks.serviceOf(host, uri.getPath())) {
            case "youtube" -> ContentLinks.youtubeVideoId(uri)
                    .<TizenLaunch>map(id -> new TizenLaunch.Dial(YOUTUBE_NAME, "v=" + id))
                    .orElseGet(() -> app(YOUTUBE_NAME, installed));
            case "netflix" -> app("Netflix", installed);
            case "primevideo" -> app("Prime Video", installed);
            default -> new TizenLaunch.Unsupported("Samsung TVs cannot open web links; they open YouTube videos"
                    + " and the YouTube, Netflix and Prime Video apps");
        };
    }

    /** The service apps whose visibility tells the session what is in front. */
    static List<TizenLaunch.App> knownApps(Optional<List<TizenApp>> installed) {
        return WELL_KNOWN_IDS.keySet().stream()
                .map(name -> app(name, installed))
                .filter(TizenLaunch.App.class::isInstance)
                .map(TizenLaunch.App.class::cast)
                .toList();
    }

    static TizenLaunch app(String name, Optional<List<TizenApp>> installed) {
        if (installed.isEmpty()) {
            return new TizenLaunch.App(WELL_KNOWN_IDS.get(name), name, "DEEP_LINK");
        }
        return installed.get().stream()
                .filter(app -> app.name().equalsIgnoreCase(name))
                .findFirst()
                .<TizenLaunch>map(app -> new TizenLaunch.App(app.appId(), app.name(),
                        app.appType() == 4 ? "NATIVE_LAUNCH" : "DEEP_LINK"))
                .orElseGet(() -> new TizenLaunch.Unsupported(name + " is not installed on this TV"));
    }

    private static Map<String, String> wellKnownIds() {
        Map<String, String> ids = new LinkedHashMap<>();
        ids.put(YOUTUBE_NAME, "111299001912");
        ids.put("Netflix", "3201907018807");
        ids.put("Prime Video", "3201910019365");
        return ids;
    }
}

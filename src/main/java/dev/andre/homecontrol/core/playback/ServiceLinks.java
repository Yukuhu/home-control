package dev.andre.homecontrol.core.playback;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Service URL grammar shared by sources, the planner's descriptions and adapters. Pure; no I/O. */
public final class ServiceLinks {

    public static final String YOUTUBE = "youtube";
    public static final String NETFLIX = "netflix";
    public static final String PRIME_VIDEO = "primevideo";
    public static final String DAZN = "dazn";
    public static final String WEB = "web";

    private static final Map<String, String> NAMES = Map.of(
            YOUTUBE, "YouTube", NETFLIX, "Netflix", PRIME_VIDEO, "Prime Video", DAZN, "DAZN", "jellyfin", "Jellyfin");

    private static final Pattern NETFLIX_PATH = Pattern.compile(
            "^(?:/[a-z]{2}(?:-[a-z]{2})?)?/(?:title|watch)/([0-9]{1,12})(?:/.*)?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern NETFLIX_ID = Pattern.compile("^[0-9]{1,12}$");
    private static final Pattern GTI = Pattern.compile(
            "amzn1\\.dv\\.gti\\.[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", Pattern.CASE_INSENSITIVE);
    private static final Pattern PRIME_DETAIL = Pattern.compile("/detail/([0-9A-Za-z]{10,40})(?:/|$)");
    private static final Pattern AMAZON_DETAIL = Pattern.compile("^/gp/video/detail/([0-9A-Z]{10})(?:/.*)?$");

    /** Opens the service's app without a title. Which URL each Android TV app claims is on the acceptance checklist. */
    private static final Map<String, URI> APP_HOMES = Map.of(
            NETFLIX, URI.create("https://www.netflix.com/browse"),
            PRIME_VIDEO, URI.create("https://app.primevideo.com/"),
            DAZN, URI.create("https://www.dazn.com/"));

    private ServiceLinks() {
    }

    public static Optional<String> displayName(String service) {
        return Optional.ofNullable(service == null ? null : NAMES.get(service));
    }

    /** The service's name, or the link's host for a plain web link. */
    public static String label(String service, URI uri) {
        return displayName(service).orElse(uri.getHost());
    }

    public static Optional<URI> appHome(String service) {
        return Optional.ofNullable(service == null ? null : APP_HOMES.get(service));
    }

    public static boolean isAppHome(URI uri) {
        return uri != null && APP_HOMES.containsValue(uri);
    }

    public static URI netflixTitle(String titleId) {
        if (titleId == null || !NETFLIX_ID.matcher(titleId).matches()) {
            throw new IllegalArgumentException("Not a Netflix title id: " + titleId);
        }
        return URI.create("https://www.netflix.com/title/" + titleId);
    }

    public static URI primeVideoDetail(String gti) {
        if (gti == null || !GTI.matcher(gti).matches()) {
            throw new IllegalArgumentException("Not a Prime Video GTI");
        }
        return URI.create("https://app.primevideo.com/detail?gti=" + gti.toLowerCase(Locale.ROOT));
    }

    public static Optional<String> netflixTitleId(URI uri) {
        if (!isOrUnder(host(uri), "netflix.com")) {
            return Optional.empty();
        }
        Matcher matcher = NETFLIX_PATH.matcher(uri.getPath() == null ? "" : uri.getPath());
        return matcher.matches() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    public static Optional<String> primeVideoGti(URI uri) {
        if (!isOrUnder(host(uri), "primevideo.com")) {
            return Optional.empty();
        }
        String query = uri.getRawQuery() == null ? "" : URLDecoder.decode(uri.getRawQuery(), StandardCharsets.UTF_8);
        for (String candidate : List.of(query, uri.getPath() == null ? "" : uri.getPath())) {
            Matcher matcher = GTI.matcher(candidate);
            if (matcher.find()) {
                return Optional.of(matcher.group().toLowerCase(Locale.ROOT));
            }
        }
        return Optional.empty();
    }

    public static URI canonical(URI uri) {
        String host = host(uri);
        String path = uri.getPath() == null ? "" : uri.getPath();
        String service = AppLinks.serviceOf(host, path);
        if (service.equals(NETFLIX)) {
            return netflixTitleId(uri).map(ServiceLinks::netflixTitle).orElse(uri);
        }
        if (!service.equals(PRIME_VIDEO)) {
            return uri;
        }
        Optional<String> gti = primeVideoGti(uri);
        if (gti.isPresent()) {
            return primeVideoDetail(gti.get());
        }
        if (isOrUnder(host, "primevideo.com")) {
            Matcher detail = PRIME_DETAIL.matcher(path);
            return detail.find() ? URI.create("https://www.primevideo.com/detail/" + detail.group(1)) : uri;
        }
        Matcher asin = AMAZON_DETAIL.matcher(path);
        return asin.matches() ? URI.create("https://" + host + "/gp/video/detail/" + asin.group(1)) : uri;
    }

    public static PlayableRef.AppLink appLink(URI uri) {
        URI link = canonical(uri);
        return new PlayableRef.AppLink(link, AppLinks.serviceOf(host(link), link.getPath()));
    }

    private static String host(URI uri) {
        return uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
    }

    private static boolean isOrUnder(String host, String domain) {
        return host.equals(domain) || host.endsWith("." + domain);
    }
}

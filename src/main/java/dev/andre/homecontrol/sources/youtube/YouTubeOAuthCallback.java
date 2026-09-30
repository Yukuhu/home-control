package dev.andre.homecontrol.sources.youtube;


import java.net.URI;
import org.springframework.web.util.UriComponentsBuilder;

/** Uses the same browser-facing origin for starting sign-in and receiving its session cookie. */
final class YouTubeOAuthCallback {
    // This app's own route: the @GetMapping value (a compile-time constant) and the redirect target it serves.
    @SuppressWarnings("java:S1075")
    static final String PATH = "/setup/sources/youtube/callback";

    private YouTubeOAuthCallback() { }

    /** The callback under {@code contextUrl}, this server's root as the browser reaches it. */
    static URI uri(URI contextUrl) {
        return UriComponentsBuilder.fromUri(contextUrl).path(PATH).replaceQuery(null).build().toUri();
    }

    static boolean supported(URI uri) {
        String host = uri.getHost();
        if (host == null) return false;
        boolean loopback = "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "[::1]".equals(host);
        return loopback && ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                || "https".equals(uri.getScheme()) && host.contains(".") && !host.matches("[0-9.]+")
                && !host.endsWith(".local");
    }

    static void requireSupported(URI uri) {
        if (!supported(uri)) {
            throw new YouTubeException(YouTubeException.Kind.INVALID_INPUT,
                    "Google browser sign-in needs an HTTPS domain or localhost. Open Home Control there,"
                            + " or use a device code with a TVs and Limited Input devices client on your LAN.");
        }
    }
}

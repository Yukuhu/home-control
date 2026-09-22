package dev.andre.homecontrol.sources.youtube;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;

/** Uses the same browser-facing origin for starting sign-in and receiving its session cookie. */
final class YouTubeOAuthCallback {
    static final String PATH = "/setup/sources/youtube/callback";

    private YouTubeOAuthCallback() { }

    static URI uri(HttpServletRequest request) {
        return ServletUriComponentsBuilder.fromRequestUri(request)
                .replacePath(request.getContextPath() + PATH).replaceQuery(null).build().toUri();
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

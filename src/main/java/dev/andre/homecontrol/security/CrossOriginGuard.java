package dev.andre.homecontrol.security;

import jakarta.servlet.http.HttpServletRequest;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Refuses cross-origin state-changing browser requests — the algorithm of Go 1.25's
 * {@code http.CrossOriginProtection}: Fetch Metadata when the browser sends it, else Origin vs Host.
 * The decision depends on the method and headers only, never on the path, so no spelling of a
 * path ({@code ;params}, percent-encoding) can step around it.
 */
public final class CrossOriginGuard {

    /** Exact, case-sensitive method names: anything else, even {@code get}, is treated as state-changing. */
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    private final Set<String> trustedOrigins;

    public CrossOriginGuard(List<String> trustedOrigins) {
        this.trustedOrigins = trustedOrigins == null ? Set.of() : trustedOrigins.stream()
                .filter(origin -> origin != null && !origin.isBlank())
                .map(origin -> origin.strip().toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    public boolean allows(HttpServletRequest request) {
        if (SAFE_METHODS.contains(request.getMethod())) {
            return true;
        }
        String origin = request.getHeader("Origin");
        if (origin != null && trustedOrigins.contains(origin.strip().toLowerCase(Locale.ROOT))) {
            return true;
        }
        String site = request.getHeader("Sec-Fetch-Site");
        if (site != null) {
            return site.equals("same-origin") || site.equals("none");
        }
        if (origin == null) {
            return true; // not a browser, or one too old to send either header
        }
        String authority = authorityOf(origin.strip());
        return authority != null && authority.equalsIgnoreCase(host(request));
    }

    /** The host[:port] of an origin, or null unless it is exactly http(s)://host[:port]. */
    private static String authorityOf(String origin) {
        URI uri;
        try {
            uri = new URI(origin);
        } catch (URISyntaxException _) {
            return null;
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                || uri.getRawUserInfo() != null || uri.getHost() == null
                || !(uri.getRawPath() == null || uri.getRawPath().isEmpty())
                || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            return null;
        }
        return uri.getRawAuthority();
    }

    private static String host(HttpServletRequest request) {
        String host = request.getHeader("Host");
        if (host != null) {
            return host.strip();
        }
        int port = request.getServerPort();
        return port == 80 || port == 443 || port <= 0 ? request.getServerName() : request.getServerName() + ":" + port;
    }
}

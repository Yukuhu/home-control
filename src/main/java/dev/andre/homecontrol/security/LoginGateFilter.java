package dev.andre.homecontrol.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Predicate;

/**
 * Once a login exists every path except the login page, its stylesheets, theme script and fonts,
 * and the handful of PWA assets a phone needs before it can even show the login page (D6) needs
 * an authenticated session — pages, JSON, SSE, scripts and artwork alike (spec §9). Without a
 * login it lets every request through. Cross-origin requests never get here: {@link CrossOriginFilter} runs first.
 */
public class LoginGateFilter extends OncePerRequestFilter {

    /**
     * Exact raw-URI matches only — never a prefix. A prefix such as {@code /icons/} would
     * reopen the {@code ;param}/percent-encoding bypass C1 fixed: a request whose raw URI is,
     * say, {@code /icons/..;/setup} starts with that prefix while the container's normalised
     * path is really {@code /setup}. Every new open asset (a new icon size, say) is one more
     * entry here, not a new prefix.
     */
    static final Set<String> OPEN_PATHS = Set.of(
            "/login", "/health", "/app.css", "/manifest.webmanifest", "/offline.html",
            "/js/theme.js",
            "/themes/catalog.js", "/themes/catalog.json",
            "/icons/icon.svg", "/icons/icon-192.png", "/icons/icon-512.png",
            "/icons/maskable-512.png", "/icons/apple-touch-icon.png");

    private final LoginService login;
    private final Predicate<String> publicAssets;
    private final BiFunction<HttpServletRequest, HttpServletResponse, LoginContext> contexts;

    /** {@code contexts} gives the login of a request's browser, from which a restarted server resumes its login. */
    public LoginGateFilter(LoginService login, Predicate<String> publicAssets,
                           BiFunction<HttpServletRequest, HttpServletResponse, LoginContext> contexts) {
        this.login = login;
        this.publicAssets = publicAssets;
        this.contexts = contexts;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!login.loginRequired()) {
            chain.doFilter(request, response);
            return;
        }
        String path = path(request);
        // A browser the server lost the session of (a restart) is let in by its remembered login, before any
        // controller runs, so that an event stream opened now is bound to a logged-in session.
        if (OPEN_PATHS.contains(path) || publicAssets.test(path) || login.isAuthenticated(request)
                || login.resume(contexts.apply(request, response))) {
            chain.doFilter(request, response);
            return;
        }
        if ("true".equals(request.getHeader("HX-Request"))) {
            response.setHeader("HX-Redirect", "/login");
            plain(response, HttpServletResponse.SC_UNAUTHORIZED, "Log in first");
        } else if ("GET".equals(request.getMethod()) && accepts(request, "text/html")) {
            String target = path + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
            response.sendRedirect(loginPage(target));
        } else if (accepts(request, "text/html")) {
            // A form posted after the login ended: back to its page once logged in, not a bare text answer.
            response.setStatus(HttpServletResponse.SC_SEE_OTHER);
            response.setHeader("Location", loginPage(sameSiteReferer(request)));
        } else {
            plain(response, HttpServletResponse.SC_UNAUTHORIZED, "Log in first");
        }
    }

    private static String loginPage(String next) {
        return "/login?next=" + URLEncoder.encode(next, StandardCharsets.UTF_8);
    }

    /** The page a form was posted from, when the browser names one on this server; else the dashboard. */
    private static String sameSiteReferer(HttpServletRequest request) {
        String referer = request.getHeader("Referer");
        String host = request.getHeader("Host");
        if (referer == null || host == null) {
            return "/";
        }
        try {
            URI page = new URI(referer);
            if (!host.equalsIgnoreCase(page.getRawAuthority()) || page.getRawPath() == null
                    || !page.getRawPath().startsWith("/")) {
                return "/";
            }
            return page.getRawPath() + (page.getRawQuery() == null ? "" : "?" + page.getRawQuery());
        } catch (URISyntaxException _) {
            return "/";
        }
    }

    /**
     * The raw request URI without the context path — not the container's decoded, normalized servlet
     * path, so {@code /login;x} or {@code /%6cogin} is never mistaken for an open path.
     */
    static String path(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        return context != null && !context.isEmpty() && uri.startsWith(context) ? uri.substring(context.length()) : uri;
    }

    private static boolean accepts(HttpServletRequest request, String type) {
        String accept = request.getHeader("Accept");
        return accept != null && accept.contains(type);
    }

    private static void plain(HttpServletResponse response, int status, String body) throws IOException {
        response.setStatus(status);
        response.setContentType("text/plain;charset=UTF-8");
        response.getWriter().write(body);
    }
}

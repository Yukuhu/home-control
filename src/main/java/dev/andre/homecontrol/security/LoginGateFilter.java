package dev.andre.homecontrol.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Once a login exists every path except the login page, its stylesheet and the handful of PWA
 * assets a phone needs before it can even show the login page (D6) needs an authenticated
 * session — pages, JSON, SSE, scripts and artwork alike (spec §9). Without a login it lets every
 * request through. Cross-origin requests never get here: {@link CrossOriginFilter} runs first.
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
            "/login", "/app.css", "/manifest.webmanifest", "/offline.html",
            "/icons/icon.svg", "/icons/icon-192.png", "/icons/icon-512.png",
            "/icons/maskable-512.png", "/icons/apple-touch-icon.png");

    private final LoginService login;

    public LoginGateFilter(LoginService login) {
        this.login = login;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!login.loginRequired()) {
            chain.doFilter(request, response);
            return;
        }
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "same-origin"); // no-referrer would make Chrome send Origin: null
        String path = path(request);
        if (OPEN_PATHS.contains(path) || login.isAuthenticated(request)) {
            chain.doFilter(request, response);
            return;
        }
        if ("true".equals(request.getHeader("HX-Request"))) {
            response.setHeader("HX-Redirect", "/login");
            plain(response, HttpServletResponse.SC_UNAUTHORIZED, "Log in first");
        } else if ("GET".equals(request.getMethod()) && accepts(request, "text/html")) {
            String target = path + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
            response.sendRedirect("/login?next=" + URLEncoder.encode(target, StandardCharsets.UTF_8));
        } else {
            plain(response, HttpServletResponse.SC_UNAUTHORIZED, "Log in first");
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

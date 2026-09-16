package dev.andre.homecontrol.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * The first filter on every request and path, before the login gate and whether or not a login
 * exists. It refuses a Host name this app does not answer to (DNS rebinding: 421, the name is not
 * echoed), then applies {@link CrossOriginGuard}, so a page on another site cannot press keys,
 * change setup or try passwords through a visitor's LAN access.
 */
public class CrossOriginFilter extends OncePerRequestFilter {

    static final int MISDIRECTED_REQUEST = 421;

    private final CrossOriginGuard guard;
    private final HostAllowlist hosts;

    public CrossOriginFilter(CrossOriginGuard guard, HostAllowlist hosts) {
        this.guard = guard;
        this.hosts = hosts;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!hosts.allows(host(request))) {
            plain(response, MISDIRECTED_REQUEST,
                    "This host name is not allowed; add it to HOME_CONTROL_ALLOWED_HOSTS to use it");
            return;
        }
        if (!guard.allows(request)) {
            plain(response, HttpServletResponse.SC_FORBIDDEN, "Cross-origin request refused");
            return;
        }
        chain.doFilter(request, response);
    }

    /** Browsers always send Host; only a request without one (HTTP/1.0, tests) falls back to the server name. */
    private static String host(HttpServletRequest request) {
        String host = request.getHeader("Host");
        return host != null ? host.strip() : request.getServerName();
    }

    private static void plain(HttpServletResponse response, int status, String body) throws IOException {
        response.setStatus(status);
        response.setContentType("text/plain;charset=UTF-8");
        response.getWriter().write(body);
    }
}

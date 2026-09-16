package dev.andre.homecontrol.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Refuses state-changing POSTs that a browser marks {@code Sec-Fetch-Site: cross-site}, so a
 * page on another site cannot press keys or forget devices through a visitor's LAN access.
 * Deliberately minimal: requests without fetch metadata (older browsers, curl) pass. A fuller
 * cross-origin check is planned to replace this class.
 */
@Component
public class CrossSitePostGuard extends OncePerRequestFilter {

    private static final String FETCH_SITE = "Sec-Fetch-Site";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !HttpMethod.POST.matches(request.getMethod())
                || !(path.startsWith("/devices/") || path.startsWith("/setup/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if ("cross-site".equalsIgnoreCase(request.getHeader(FETCH_SITE))) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType(MediaType.TEXT_PLAIN_VALUE);
            response.getWriter().write("Requests from other sites are not allowed");
            return;
        }
        chain.doFilter(request, response);
    }
}

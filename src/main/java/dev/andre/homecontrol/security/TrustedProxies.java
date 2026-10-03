package dev.andre.homecontrol.security;

import org.apache.catalina.valves.RemoteIpValve;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.core.env.PropertyResolver;

import java.net.InetAddress;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Behind a reverse proxy every request comes from the proxy's address, so every browser would share one login limit.
 * The proxies named in {@code home-control.security.trusted-proxies} may say whom they forward for
 * ({@code X-Forwarded-For}) and to which scheme, host and port the browser sent it ({@code X-Forwarded-Proto},
 * {@code -Host}, {@code -Port}); nobody else may. Tomcat's {@link RemoteIpValve} then gives a forwarded request the
 * browser's address and the address the browser used, as the YouTube sign-in's callback needs, and a request
 * straight from the network keeps its own. The {@code Host} header itself, which the origin checks read, is left
 * as it came. Spring Boot's own forwarded-header support believes these headers from every private address, so the
 * two cannot be combined.
 */
final class TrustedProxies implements WebServerFactoryCustomizer<TomcatServletWebServerFactory> {

    private static final Logger log = LoggerFactory.getLogger(TrustedProxies.class);

    private final List<String> addresses;

    /**
     * Refuses to start beside Spring Boot's own forwarded-header support, which believes these headers from every
     * private address: its strategy setting, or Tomcat's remote-IP headers, which add Boot's valve as well.
     */
    TrustedProxies(List<String> proxies, PropertyResolver settings) {
        this.addresses = proxies == null ? List.of() : List.copyOf(proxies);
        String strategy = settings.getProperty("server.forward-headers-strategy", "none").strip();
        boolean springForwards = !strategy.isEmpty() && !"none".equalsIgnoreCase(strategy);
        if (addresses.isEmpty()) {
            if (springForwards) {
                log.warn("SERVER_FORWARD_HEADERS_STRATEGY={} believes X-Forwarded headers from every client, who can"
                        + " so pick its own login limit; name the reverse proxy in HOME_CONTROL_TRUSTED_PROXIES and"
                        + " remove that setting", strategy);
            }
            return;
        }
        if (springForwards) {
            throw refusal("server.forward-headers-strategy (SERVER_FORWARD_HEADERS_STRATEGY)");
        }
        for (String header : List.of("server.tomcat.remoteip.remote-ip-header",
                "server.tomcat.remoteip.protocol-header")) {
            String value = settings.getProperty(header);
            if (value != null && !value.isBlank()) {
                throw refusal(header);
            }
        }
    }

    private static IllegalStateException refusal(String setting) {
        return new IllegalStateException("home-control.security.trusted-proxies (HOME_CONTROL_TRUSTED_PROXIES)"
                + " replaces " + setting + ", which believes forwarded headers from every private address;"
                + " remove that setting");
    }

    /** Exactly these addresses, as Tomcat reports a peer's address: {@code ::1} is {@code 0:0:0:0:0:0:0:1}. */
    static String pattern(List<String> addresses) {
        return addresses.stream()
                .map(address -> Pattern.quote(InetAddress.ofLiteral(address.strip()).getHostAddress()))
                .collect(Collectors.joining("|"));
    }

    @Override
    public void customize(TomcatServletWebServerFactory factory) {
        if (addresses.isEmpty()) {
            return;
        }
        RemoteIpValve valve = new RemoteIpValve();
        valve.setInternalProxies(pattern(addresses));
        valve.setRemoteIpHeader("X-Forwarded-For");
        valve.setProtocolHeader("X-Forwarded-Proto");
        valve.setHostHeader("X-Forwarded-Host");
        valve.setPortHeader("X-Forwarded-Port");
        factory.addEngineValves(valve);
    }
}

package dev.andre.homecontrol.security;

import org.apache.catalina.valves.RemoteIpValve;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;

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
 * as it came. Spring's own forwarded-header support trusts these headers from
 * any client, so the two cannot be combined.
 */
final class TrustedProxies implements WebServerFactoryCustomizer<TomcatServletWebServerFactory> {

    private final List<String> addresses;

    TrustedProxies(List<String> proxies, String forwardHeadersStrategy) {
        this.addresses = proxies == null ? List.of() : List.copyOf(proxies);
        if (!addresses.isEmpty() && forwardHeadersStrategy != null && !forwardHeadersStrategy.isBlank()
                && !"none".equalsIgnoreCase(forwardHeadersStrategy.strip())) {
            throw new IllegalStateException("home-control.security.trusted-proxies (HOME_CONTROL_TRUSTED_PROXIES)"
                    + " replaces server.forward-headers-strategy (SERVER_FORWARD_HEADERS_STRATEGY), which trusts"
                    + " forwarded headers from every client; remove that setting");
        }
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

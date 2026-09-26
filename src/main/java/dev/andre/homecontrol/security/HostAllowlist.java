package dev.andre.homecontrol.security;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The host names this app answers to. A DNS-rebinding page reaches the app under the attacker's
 * own name, so Origin and Host agree and only the name gives it away: IP literals, localhost,
 * single-label names and LAN suffixes are allowed, plus the hosts of trusted origins and
 * {@code home-control.security.allowed-hosts} (exact names or {@code *.suffix}).
 */
public final class HostAllowlist {

    private static final List<String> LAN_SUFFIXES = List.of(".local", ".lan", ".home.arpa", ".internal");
    private static final Pattern LABEL = Pattern.compile("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?");
    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");
    private static final Pattern PORT = Pattern.compile("\\d{1,5}");
    private static final Pattern IPV6_CHARS = Pattern.compile("[0-9a-fA-F:.]+");

    private final Set<String> exactHosts = new HashSet<>();
    private final Set<String> suffixes = new HashSet<>();

    public HostAllowlist(List<String> trustedOrigins, List<String> allowedHosts) {
        if (trustedOrigins != null) {
            trustedOrigins.stream().filter(origin -> origin != null && !origin.isBlank())
                    .map(HostAllowlist::hostOfOrigin)
                    .filter(host -> host != null && isName(host))
                    .forEach(exactHosts::add);
        }
        if (allowedHosts != null) {
            for (String entry : allowedHosts) {
                String host = entry == null ? "" : entry.strip().toLowerCase(Locale.ROOT);
                if (host.startsWith("*.") && isName(host.substring(2)) && host.substring(2).contains(".")) {
                    suffixes.add(host.substring(1));
                } else if (isName(host)) {
                    exactHosts.add(host);
                }
            }
        }
    }

    /** Whether a {@code Host} header value (name or IP literal, optional port) names this app. */
    public boolean allows(String hostHeader) {
        if (hostHeader == null) {
            return false;
        }
        String host;
        String port = null;
        if (hostHeader.startsWith("[")) {
            int close = hostHeader.indexOf(']');
            if (close < 0) {
                return false;
            }
            String rest = hostHeader.substring(close + 1);
            if (!rest.isEmpty()) {
                if (!rest.startsWith(":")) {
                    return false;
                }
                port = rest.substring(1);
            }
            if (port != null && !validPort(port)) {
                return false;
            }
            return isIpv6(hostHeader.substring(1, close));
        }
        int colon = hostHeader.indexOf(':');
        if (colon >= 0) {
            host = hostHeader.substring(0, colon);
            port = hostHeader.substring(colon + 1);
            if (!validPort(port)) {
                return false;
            }
        } else {
            host = hostHeader;
        }
        host = host.toLowerCase(Locale.ROOT);
        if (IPV4.matcher(host).matches()) {
            return isIpv4(host);
        }
        if (!isName(host)) {
            return false;
        }
        if (host.equals("localhost") || !host.contains(".") || exactHosts.contains(host)) {
            return true;
        }
        return LAN_SUFFIXES.stream().anyMatch(host::endsWith) || suffixes.stream().anyMatch(host::endsWith);
    }

    private static boolean validPort(String port) {
        return PORT.matcher(port).matches() && Integer.parseInt(port) >= 1 && Integer.parseInt(port) <= 65535;
    }

    /** Dot-separated DNS labels, no empty label and no trailing dot. */
    private static boolean isName(String host) {
        if (host.isEmpty() || host.length() > 253) {
            return false;
        }
        for (String label : host.split("\\.", -1)) {
            if (!LABEL.matcher(label).matches()) {
                return false;
            }
        }
        return true;
    }

    private static boolean isIpv4(String host) {
        try {
            Inet4Address.ofLiteral(host); // never resolves
            return true;
        } catch (IllegalArgumentException _) {
            return false;
        }
    }

    private static boolean isIpv6(String literal) {
        if (!IPV6_CHARS.matcher(literal).matches() || !literal.contains(":")) {
            return false;
        }
        try {
            Inet6Address.ofLiteral(literal); // never resolves
            return true;
        } catch (IllegalArgumentException _) {
            return false;
        }
    }

    private static String hostOfOrigin(String origin) {
        try {
            String host = new URI(origin.strip()).getHost();
            return host == null ? null : host.toLowerCase(Locale.ROOT);
        } catch (URISyntaxException _) {
            return null;
        }
    }
}

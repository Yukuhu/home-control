package dev.andre.homecontrol.sources.sports.calendar;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * Which calendar links the server may fetch. LAN hosts are allowed (self-hosted calendars); this machine
 * (host networking), link-local (cloud metadata) and multicast addresses are not. Checked on every hop.
 */
public class CalendarUrlPolicy {

    @FunctionalInterface
    public interface HostResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    static final int MAX_LENGTH = 2048;

    private final boolean allowLoopback;
    private final HostResolver resolver;

    public CalendarUrlPolicy(boolean allowLoopback) {
        this(allowLoopback, InetAddress::getAllByName);
    }

    public CalendarUrlPolicy(boolean allowLoopback, HostResolver resolver) {
        this.allowLoopback = allowLoopback;
        this.resolver = resolver;
    }

    public URI parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Enter a calendar link");
        }
        String url = raw.strip();
        if (url.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("That calendar link is too long");
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("That is not a valid link", e);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (scheme.equals("webcal") || scheme.equals("webcals")) {
            try {
                uri = new URI("https" + url.substring(uri.getScheme().length()));
            } catch (URISyntaxException e) {
                throw new IllegalArgumentException("That is not a valid link", e);
            }
        } else if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException("Use an http, https or webcal link");
        }
        if (uri.getRawUserInfo() != null) {
            throw new IllegalArgumentException(
                    "Links with a user name or password are not supported; use the calendar's secret link instead");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("That is not a valid link");
        }
        return uri;
    }

    public void checkAddress(URI uri) {
        String host = uri.getHost();
        String lookup = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        InetAddress[] addresses;
        try {
            addresses = resolver.resolve(lookup);
        } catch (UnknownHostException e) {
            throw new CalendarFetchException(CalendarFetchException.Kind.UNREACHABLE, "Could not find " + host);
        }
        for (InetAddress address : addresses) {
            if (address.isAnyLocalAddress() || address.isLinkLocalAddress() || address.isMulticastAddress()
                    || (address.isLoopbackAddress() && !allowLoopback)) {
                throw new CalendarFetchException(CalendarFetchException.Kind.BLOCKED, "Home Control does not load calendars from "
                        + host + ": that address belongs to this machine or its network link");
            }
        }
    }
}

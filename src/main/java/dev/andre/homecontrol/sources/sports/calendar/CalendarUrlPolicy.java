package dev.andre.homecontrol.sources.sports.calendar;

import dev.andre.homecontrol.core.content.ContentSourceException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * Which calendar links the server may fetch. LAN hosts are allowed (self-hosted calendars); this machine
 * (host networking), link-local (cloud metadata) and multicast addresses are not. Checked on every hop.
 */
public class CalendarUrlPolicy {

    @FunctionalInterface
    public interface HostResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private final boolean allowLoopback;
    private final HostResolver resolver;

    public CalendarUrlPolicy(boolean allowLoopback) {
        this(allowLoopback, InetAddress::getAllByName);
    }

    public CalendarUrlPolicy(boolean allowLoopback, HostResolver resolver) {
        this.allowLoopback = allowLoopback;
        this.resolver = resolver;
    }

    /**
     * Looks the host up and returns its addresses if every one of them may be fetched from; otherwise throws.
     * The fetcher connects to exactly these addresses, so the check and the connection cannot disagree.
     * Accepts an IPv6 literal with or without its URI brackets.
     */
    public InetAddress[] addresses(String host) {
        String lookup = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        InetAddress[] addresses;
        try {
            addresses = resolver.resolve(lookup);
        } catch (UnknownHostException _) {
            throw new CalendarFetchException(ContentSourceException.Kind.UNREACHABLE, "Could not find " + host);
        }
        for (InetAddress address : addresses) {
            if (address.isAnyLocalAddress() || address.isLinkLocalAddress() || address.isMulticastAddress()
                    || (address.isLoopbackAddress() && !allowLoopback)) {
                throw new CalendarFetchException(ContentSourceException.Kind.BLOCKED, "Home Control does not load calendars from "
                        + host + ": that address belongs to this machine or its network link");
            }
        }
        return addresses;
    }

    public void checkAddress(URI uri) {
        addresses(uri.getHost());
    }
}

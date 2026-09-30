package dev.andre.homecontrol.sources.http;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Objects;

/**
 * Where a content source may connect. The LAN and the internet are allowed; this machine's any-local address, its
 * link-local network (cloud metadata lives there) and multicast never are, and loopback only when the source allows
 * it. An IPv4-mapped IPv6 address is judged as the IPv4 address it carries. The connection must go to exactly the
 * addresses returned, so a host cannot pass with one address and be reached at another.
 */
public final class OutboundAddressPolicy {

    /** Looks a host name up; {@code InetAddress::getAllByName} outside tests. */
    @FunctionalInterface
    public interface Resolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    /** The host resolves, but to an address no source may reach. An {@link UnknownHostException} so it passes
     * through HttpClient's DNS hook unchanged. */
    public static final class BlockedAddressException extends UnknownHostException {
        public BlockedAddressException() {
            super("address not allowed");
        }
    }

    private final boolean allowLoopback;
    private final Resolver resolver;

    public OutboundAddressPolicy(boolean allowLoopback) {
        this(allowLoopback, InetAddress::getAllByName);
    }

    public OutboundAddressPolicy(boolean allowLoopback, Resolver resolver) {
        this.allowLoopback = allowLoopback;
        this.resolver = Objects.requireNonNull(resolver);
    }

    /** The host's addresses when every one of them may be reached; accepts an IPv6 literal with or without brackets. */
    public InetAddress[] addresses(String host) throws UnknownHostException {
        String bare = unbracketed(host);
        InetAddress literal = literal(bare);
        InetAddress[] resolved = literal != null ? new InetAddress[] {literal} : resolver.resolve(bare);
        if (resolved == null || resolved.length == 0) {
            throw new UnknownHostException("unknown host");
        }
        // A resolver may retain its array. Never hand out an array it can change after the check.
        InetAddress[] checked = resolved.clone();
        for (InetAddress address : checked) {
            if (address == null) {
                throw new UnknownHostException("unknown host");
            }
            if (!allowed(address)) {
                throw new BlockedAddressException();
            }
        }
        return checked;
    }

    /** Whether a host is an IP literal, which HttpClient may connect to without asking the DNS hook. */
    public static boolean isLiteral(String host) {
        return host != null && literal(unbracketed(host)) != null;
    }

    private boolean allowed(InetAddress address) throws UnknownHostException {
        InetAddress judged = unmapped(address);
        return !(judged.isAnyLocalAddress() || judged.isLinkLocalAddress() || judged.isMulticastAddress()
                || (!allowLoopback && judged.isLoopbackAddress()));
    }

    private static InetAddress unmapped(InetAddress address) throws UnknownHostException {
        byte[] bytes = address.getAddress();
        boolean mapped = bytes.length == 16 && Arrays.equals(bytes, 0, 10, new byte[10], 0, 10)
                && bytes[10] == (byte) 0xff && bytes[11] == (byte) 0xff;
        return mapped ? InetAddress.getByAddress(Arrays.copyOfRange(bytes, 12, 16)) : address;
    }

    private static String unbracketed(String host) {
        return host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
    }

    private static InetAddress literal(String host) {
        try {
            return InetAddress.ofLiteral(host);
        } catch (IllegalArgumentException _) {
            return null;
        }
    }
}

package dev.andre.homecontrol.sources.workflows;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Objects;

/** LAN-friendly destination policy. The transport must connect to the returned, checked addresses. */
public final class WorkflowUrlPolicy {
    @FunctionalInterface
    public interface HostResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private final boolean allowLoopback;
    private final HostResolver resolver;

    public WorkflowUrlPolicy(boolean allowLoopback, HostResolver resolver) {
        this.allowLoopback = allowLoopback;
        this.resolver = Objects.requireNonNull(resolver);
    }

    public URI parse(String value) {
        if (value == null || value.isBlank() || value.length() > 8_192) throw invalidUrl();
        try {
            URI uri = new URI(value);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getHost().isBlank() || uri.getHost().contains("%")
                    || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                    || uri.getPort() == 0 || uri.getPort() > 65_535) throw invalidUrl();
            return uri;
        } catch (URISyntaxException _) {
            throw invalidUrl();
        }
    }

    public InetAddress[] addresses(String host) throws UnknownHostException {
        InetAddress literal = literal(host);
        InetAddress[] resolved = literal == null ? resolver.resolve(host) : new InetAddress[]{literal};
        if (resolved == null || resolved.length == 0) throw invalidAddress();
        // A resolver may retain its array. Never expose an array it can mutate after validation.
        InetAddress[] checked = resolved.clone();
        for (InetAddress address : checked) {
            if (address == null) throw invalidAddress();
            byte[] bytes = address.getAddress();
            if (bytes.length == 16 && Arrays.equals(bytes, 0, 10, new byte[10], 0, 10)
                    && bytes[10] == (byte) 0xff && bytes[11] == (byte) 0xff) {
                address = InetAddress.getByAddress(Arrays.copyOfRange(bytes, 12, 16));
            }
            if (address.isAnyLocalAddress() || address.isLinkLocalAddress() || address.isMulticastAddress()
                    || (!allowLoopback && address.isLoopbackAddress())) throw invalidAddress();
        }
        return checked;
    }

    static InetAddress literal(String host) {
        try { return InetAddress.ofLiteral(host); }
        catch (IllegalArgumentException _) { return null; }
    }

    public boolean sameOrigin(URI first, URI second) {
        return first.getScheme().equalsIgnoreCase(second.getScheme())
                && first.getHost().equalsIgnoreCase(second.getHost()) && port(first) == port(second);
    }

    private static int port(URI uri) {
        return uri.getPort() >= 0 ? uri.getPort() : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static WorkflowException invalidUrl() {
        return new WorkflowException(WorkflowException.Stage.FETCH, "invalid HTTP URL");
    }

    private static UnknownHostException invalidAddress() {
        return new UnknownHostException("Destination address is not allowed");
    }
}

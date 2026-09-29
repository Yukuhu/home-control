package dev.andre.homecontrol.device;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Host names resolved before the registry lock is taken, so comparing hosts under it never waits on DNS. A host that
 * was not resolved (registered meanwhile, or unknown) is compared by name only.
 */
final class HostAddresses {

    private final Map<String, InetAddress> resolved;

    private HostAddresses(Map<String, InetAddress> resolved) {
        this.resolved = resolved;
    }

    static HostAddresses resolve(Collection<String> hosts, Function<String, Optional<InetAddress>> resolver) {
        Map<String, InetAddress> resolved = new HashMap<>();
        for (String host : new LinkedHashSet<>(hosts)) {
            if (host != null) {
                resolver.apply(host).ifPresent(address -> resolved.put(host.toLowerCase(Locale.ROOT), address));
            }
        }
        return new HostAddresses(resolved);
    }

    /** Literal IPs never touch DNS; a name is resolved, which only happens while pairing, never per command. */
    static Optional<InetAddress> lookup(String host) {
        try {
            return Optional.of(InetAddress.getByName(host));
        } catch (UnknownHostException _) {
            return Optional.empty();
        }
    }

    /** Equal ignoring case, or both resolved to the same address. */
    boolean same(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        if (a.equalsIgnoreCase(b)) {
            return true;
        }
        InetAddress first = resolved.get(a.toLowerCase(Locale.ROOT));
        InetAddress second = resolved.get(b.toLowerCase(Locale.ROOT));
        return first != null && first.equals(second);
    }
}

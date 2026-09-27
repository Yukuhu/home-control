package dev.andre.homecontrol.core;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.regex.Pattern;

/** Host strings as the registry and discovery see them. */
public final class Hosts {

    private static final int MAX_NAME_LENGTH = 253;
    /** One part of a name between its dots: letters, digits and underscores, with hyphens inside. */
    private static final Pattern LABEL = Pattern.compile("\\w(?:[\\w-]{0,61}\\w)?");
    /** Two colons at least, as every IPv6 address has; then a name and a port are never taken for one. */
    private static final Pattern IPV6 = Pattern.compile("(?=(?:[^:]*:){2})[0-9A-Fa-f:.]{2,45}(?:%[\\w.-]{1,32})?");

    private Hosts() {
    }

    /**
     * True for a host name or an IP address and nothing else. Adapters build the address of a device from
     * its host, and a host with a slash, a question mark or an at sign in it would send their requests
     * elsewhere: {@code 127.0.0.1/admin?} is a page of this machine, not a television. An IPv4 address
     * has the form of a name, so it needs no rule of its own.
     */
    public static boolean isValid(String host) {
        if (host == null) {
            return false;
        }
        return IPV6.matcher(host).matches() || isName(host);
    }

    private static boolean isName(String host) {
        if (host.isEmpty() || host.length() > MAX_NAME_LENGTH) {
            return false;
        }
        String name = host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
        for (String label : name.split("\\.", -1)) {
            if (!LABEL.matcher(label).matches()) {
                return false;
            }
        }
        return true;
    }

    /** The host as the authority of a URL needs it, an IPv6 address in brackets; refuses what is not a host. */
    public static String authority(String host) {
        if (!isValid(host)) {
            throw new IllegalArgumentException("Not a host name or an IP address: " + host);
        }
        return host.indexOf(':') >= 0 ? "[" + host + "]" : host;
    }

    /**
     * Equal ignoring case, or resolving to the same address. Literal IPs never touch DNS; a host
     * name is resolved, which only happens while pairing, never per command or in a listener.
     */
    public static boolean same(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        if (a.equalsIgnoreCase(b)) {
            return true;
        }
        try {
            return InetAddress.getByName(a).equals(InetAddress.getByName(b));
        } catch (UnknownHostException _) {
            return false;
        }
    }
}

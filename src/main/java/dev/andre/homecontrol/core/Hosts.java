package dev.andre.homecontrol.core;

import java.net.InetAddress;
import java.net.UnknownHostException;

/** Host strings as the registry and discovery see them. */
public final class Hosts {

    private Hosts() {
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

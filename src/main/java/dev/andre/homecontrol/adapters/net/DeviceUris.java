package dev.andre.homecontrol.adapters.net;

import java.net.URI;
import java.net.URISyntaxException;

/** URLs of a device on the LAN, from their parts, so that an IPv6 address gets the brackets a URL needs. */
public final class DeviceUris {

    private DeviceUris() {
    }

    /**
     * {@code scheme://host:port} followed by {@code pathAndQuery}, which must already be encoded and is kept as given.
     * A host that no URL can carry is refused.
     */
    public static URI of(String scheme, String host, int port, String pathAndQuery) {
        URI base;
        try {
            base = new URI(scheme, null, host, port, null, null, null);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Not a host name or an IP address: " + host, e);
        }
        return URI.create(base + pathAndQuery);
    }
}

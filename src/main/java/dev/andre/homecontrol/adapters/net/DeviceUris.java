package dev.andre.homecontrol.adapters.net;

import java.net.URI;
import java.net.URISyntaxException;

/** URLs of a device on the LAN, from their parts, so that an IPv6 address gets the brackets a URL needs. */
public final class DeviceUris {

    private DeviceUris() {
    }

    /**
     * {@code scheme://host:port} followed by {@code pathAndQuery}, which must already be encoded and is kept as given.
     * A host that no URL can carry is refused, and so is one that would change the URL: {@code 127.0.0.1/admin?} would
     * reach a page of this machine, not a television.
     */
    public static URI of(String scheme, String host, int port, String pathAndQuery) {
        URI base;
        try {
            base = new URI(scheme, null, host, port, null, null, null);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Not a host name or an IP address: " + host, e);
        }
        String authority = host.indexOf(':') >= 0 ? "[" + host + "]" : host;
        if (!authority.equalsIgnoreCase(base.getHost()) || base.getPort() != port || base.getRawUserInfo() != null
                || !base.getRawPath().isEmpty() || base.getRawQuery() != null || base.getRawFragment() != null) {
            throw new IllegalArgumentException("Not a host name or an IP address: " + host);
        }
        return URI.create(base + pathAndQuery);
    }
}

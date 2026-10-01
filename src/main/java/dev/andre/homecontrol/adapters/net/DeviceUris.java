package dev.andre.homecontrol.adapters.net;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;

/** URLs of a device on the LAN, from their parts, so that an IPv6 address gets the brackets a URL needs. */
public final class DeviceUris {

    private DeviceUris() {
    }

    /**
     * {@code scheme://host:port} followed by {@code pathAndQuery}, which must already be encoded and is kept as given.
     * A host that no URL can carry makes the device unreachable, as a {@link MalformedURLException}: an underscore
     * or a numeric last label is a name the setup page accepts. A host that would change the URL is refused the same
     * way, and nothing is sent: {@code 127.0.0.1/admin?} would reach a page of this machine, not a television.
     */
    public static URI of(String scheme, String host, int port, String pathAndQuery) throws MalformedURLException {
        URI base;
        try {
            base = new URI(scheme, null, host, port, null, null, null);
        } catch (URISyntaxException e) {
            throw notAHost(host, e);
        }
        String authority = host.indexOf(':') >= 0 ? "[" + host + "]" : host;
        if (!authority.equalsIgnoreCase(base.getHost()) || base.getPort() != port || base.getRawUserInfo() != null
                || !base.getRawPath().isEmpty() || base.getRawQuery() != null || base.getRawFragment() != null) {
            throw notAHost(host, null);
        }
        return URI.create(base + pathAndQuery);
    }

    private static MalformedURLException notAHost(String host, Throwable cause) {
        MalformedURLException refused = new MalformedURLException("No URL can carry the host " + host);
        refused.initCause(cause);
        return refused;
    }
}

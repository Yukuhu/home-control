package dev.andre.homecontrol.discovery.ssdp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.OptionalLong;
import java.util.regex.Pattern;

/**
 * The only way device-announced XML (descriptions, SCPDs) is fetched: from an address the device
 * announced itself from, over plain HTTP, with a hard size cap. Shared by {@link SsdpDiscovery}
 * and every adapter that re-reads a description on its own (UPnP renderers, Sonos).
 */
public final class DeviceFetch {

    /** A device description or SCPD is small XML; anything past this is refused rather than read into memory. */
    public static final long MAX_DESCRIPTION_BYTES = 64 * 1024;
    public static final String USER_AGENT = "Linux/1 UPnP/1.1 HomeControl/1";
    private static final Pattern IPV4_LITERAL = Pattern.compile(
            "^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$");

    private DeviceFetch() {
    }

    /**
     * Only ever fetch a description from the address it was announced from (spec §1.1's LOCATION
     * is carried in an unauthenticated UDP payload, so trusting it as-is would let one forged
     * datagram make this appliance issue an HTTP GET to any URL of the sender's choosing — a
     * server-side request forgery into the LAN or, via a public multicast relay, further still).
     * {@code https} and other schemes are refused outright (no adapter's UPnP services use them);
     * the host must be an IP literal so nothing here ever triggers a DNS lookup driven by an
     * unauthenticated datagram, and that literal must equal the datagram's sender; the port must
     * be explicit, matching every real UPnP LOCATION.
     */
    public static boolean isSafeToFetch(URI location, InetAddress sender) {
        if (location == null || sender == null || !"http".equalsIgnoreCase(location.getScheme())) {
            return false;
        }
        String host = location.getHost();
        if (host == null || !isIpLiteral(host) || location.getPort() <= 0) {
            return false;
        }
        try {
            return InetAddress.getByName(host).equals(sender);
        } catch (UnknownHostException e) {
            // Unreachable: an address that passed isIpLiteral never performs a lookup and never fails.
            return false;
        }
    }

    /** {@link #isSafeToFetch(URI, InetAddress)} for an address kept as text (an {@link SsdpService#address()}). */
    public static boolean isSafeToFetch(URI location, String announcedAddress) {
        if (announcedAddress == null || !isIpLiteral(announcedAddress)) {
            return false;
        }
        try {
            return isSafeToFetch(location, InetAddress.getByName(announcedAddress));
        } catch (UnknownHostException e) {
            return false;
        }
    }

    /** True only for a textual IPv4/IPv6 address — never a name that would need a DNS lookup to resolve. */
    public static boolean isIpLiteral(String host) {
        if (host == null || host.isEmpty()) {
            return false;
        }
        if (IPV4_LITERAL.matcher(host).matches()) {
            return true;
        }
        // An IPv6 literal contains only hex digits, ':', '.' (an embedded IPv4 tail) and '%'
        // (a zone id) — never a letter outside a-f, so this can never accidentally match a DNS hostname.
        return host.indexOf(':') >= 0 && host.chars().allMatch(c ->
                Character.isDigit(c) || c == ':' || c == '.' || c == '%' || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'));
    }

    /**
     * GETs {@code url} and returns its body, or throws: any status but 200 (redirects included —
     * clients built for devices never follow them), a declared or actual body over {@code maxBytes}.
     * The caller has already decided {@code url} is safe to fetch.
     */
    public static byte[] get(HttpClient http, URI url, Duration timeout, long maxBytes)
            throws IOException, InterruptedException {
        HttpResponse<InputStream> response = http.send(
                HttpRequest.newBuilder(url).timeout(timeout).header("User-Agent", USER_AGENT).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream body = response.body()) {
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode());
            }
            OptionalLong contentLength = contentLength(response);
            if (contentLength.isPresent() && contentLength.getAsLong() > maxBytes) {
                throw new IOException("declares " + contentLength.getAsLong() + " bytes, over the " + maxBytes + "-byte cap");
            }
            byte[] bytes = readAtMost(body, maxBytes);
            if (bytes == null) {
                throw new IOException("exceeds the " + maxBytes + "-byte cap");
            }
            return bytes;
        }
    }

    public static OptionalLong contentLength(HttpResponse<?> response) {
        try {
            return response.headers().firstValueAsLong("Content-Length");
        } catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
    }

    /** Reads at most {@code maxBytes} from {@code in}; {@code null} if the stream had more than that. */
    public static byte[] readAtMost(InputStream in, long maxBytes) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        long total = 0;
        int read;
        while ((read = in.read(chunk)) != -1) {
            total += read;
            if (total > maxBytes) {
                return null;
            }
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }
}

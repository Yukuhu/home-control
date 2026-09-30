package dev.andre.homecontrol.sources.http;

import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * One GET or POST for a {@link GuardedHttpClient}. Headers are sent in order; a later header replaces an earlier one
 * of the same name. Printing it shows the method and host only.
 *
 * @param maxBytes    the body cap for this request, or 0 for the profile's
 * @param waitForSlot whether to wait for a free slot (until the deadline) rather than fail at once when all are busy
 * @param notAfter    a {@link System#nanoTime()} the whole exchange must end by, on top of the profile's deadline
 */
public record OutboundRequest(String method, URI uri, Map<String, String> headers, byte[] body, String contentType,
                              int maxBytes, boolean waitForSlot, OptionalLong notAfter) {

    public OutboundRequest {
        if (!"GET".equals(method) && !"POST".equals(method)) {
            throw new IllegalArgumentException("Only GET and POST are supported");
        }
        Objects.requireNonNull(uri);
        headers = Collections.unmodifiableMap(new LinkedHashMap<>(headers));
        if (maxBytes < 0) {
            throw new IllegalArgumentException("A body cap cannot be negative");
        }
        Objects.requireNonNull(notAfter);
    }

    public static OutboundRequest get(URI uri) {
        return new OutboundRequest("GET", uri, Map.of(), null, null, 0, true, OptionalLong.empty());
    }

    public static OutboundRequest post(URI uri, byte[] body, String contentType) {
        return new OutboundRequest("POST", uri, Map.of(), body.clone(), contentType, 0, true, OptionalLong.empty());
    }

    public OutboundRequest header(String name, String value) {
        Map<String, String> more = new LinkedHashMap<>(headers);
        more.put(name, value);
        return new OutboundRequest(method, uri, more, body, contentType, maxBytes, waitForSlot, notAfter);
    }

    /** A body cap for this request instead of the profile's, higher or lower. */
    public OutboundRequest limitedTo(int bytes) {
        return new OutboundRequest(method, uri, headers, body, contentType, bytes, waitForSlot, notAfter);
    }

    /** Fails at once with {@link OutboundFailure#BUSY} when every slot is taken. */
    public OutboundRequest failingFastWhenBusy() {
        return new OutboundRequest(method, uri, headers, body, contentType, maxBytes, false, notAfter);
    }

    /** Waits for a slot until {@code nanoTime} and ends the exchange by then at the latest. */
    public OutboundRequest endingBy(long nanoTime) {
        return new OutboundRequest(method, uri, headers, body, contentType, maxBytes, true, OptionalLong.of(nanoTime));
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof OutboundRequest that && method.equals(that.method) && uri.equals(that.uri)
                && headers.equals(that.headers) && Arrays.equals(body, that.body)
                && Objects.equals(contentType, that.contentType) && maxBytes == that.maxBytes
                && waitForSlot == that.waitForSlot && notAfter.equals(that.notAfter);
    }

    @Override
    public int hashCode() {
        return Objects.hash(method, uri, headers, Arrays.hashCode(body), contentType, maxBytes, waitForSlot, notAfter);
    }

    @Override
    public String toString() {
        return "OutboundRequest[" + method + " " + uri.getHost() + "]";
    }
}

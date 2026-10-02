package dev.andre.homecontrol.sources.http;

import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;

/**
 * One GET or POST for a {@link GuardedHttpClient}. Headers are sent in order; a later header replaces an earlier one
 * of the same name. Printing it shows the method and host only.
 *
 * @param maxBytes    the body cap for this request, or 0 for the profile's
 * @param waitForSlot whether to wait for a free slot (until the deadline) rather than fail at once when all are busy
 * @param notAfter    a {@link System#nanoTime()} the whole exchange must end by, on top of the profile's deadline
 * @param errorBody   whether to read the body of an answer other than 200; otherwise it is dropped unread
 */
public record OutboundRequest(String method, URI uri, Map<String, String> headers, byte[] body, String contentType,
                              int maxBytes, boolean waitForSlot, OptionalLong notAfter,
                              boolean errorBody) {

    public OutboundRequest {
        if (!"GET".equals(method) && !"POST".equals(method)) {
            throw new IllegalArgumentException("Only GET and POST are supported");
        }
        Objects.requireNonNull(uri);
        headers = Collections.unmodifiableMap(new LinkedHashMap<>(headers));
        if (maxBytes < 0) {
            throw new IllegalArgumentException("A body cap cannot be negative");
        }
        if (maxBytes == Integer.MAX_VALUE) {
            throw new IllegalArgumentException("A body cap needs room for the one byte more that shows it was exceeded");
        }
        Objects.requireNonNull(notAfter);
    }

    public static OutboundRequest get(URI uri) {
        return new OutboundRequest("GET", uri, Map.of(), null, null, 0, true, OptionalLong.empty(), false);
    }

    public static OutboundRequest post(URI uri, byte[] body, String contentType) {
        return new OutboundRequest("POST", uri, Map.of(), body.clone(), contentType, 0, true, OptionalLong.empty(), false);
    }

    public OutboundRequest header(String name, String value) {
        Map<String, String> more = new LinkedHashMap<>(headers);
        more.put(name, value);
        return new OutboundRequest(method, uri, more, body, contentType, maxBytes, waitForSlot, notAfter, errorBody);
    }

    /** A body cap for this request instead of the profile's, higher or lower. */
    public OutboundRequest limitedTo(int bytes) {
        return new OutboundRequest(method, uri, headers, body, contentType, bytes, waitForSlot, notAfter, errorBody);
    }

    /** Fails at once with {@link OutboundFailure#BUSY} when every slot is taken. */
    public OutboundRequest failingFastWhenBusy() {
        return new OutboundRequest(method, uri, headers, body, contentType, maxBytes, false, notAfter, errorBody);
    }

    /**
     * Waits for a slot until {@code nanoTime}, which may be later than the profile's deadline allows (a workflow run
     * queues its calls); once admitted, the exchange ends within the profile's deadline or by {@code nanoTime},
     * whichever comes first. A request that {@linkplain #failingFastWhenBusy() fails fast when busy} keeps doing so.
     */
    public OutboundRequest endingBy(long nanoTime) {
        return new OutboundRequest(method, uri, headers, body, contentType, maxBytes, waitForSlot,
                OptionalLong.of(nanoTime), errorBody);
    }

    /** The same request with only the headers named in {@code names} (lower case), whatever case they were set in. */
    public OutboundRequest keepingOnly(Set<String> names) {
        Map<String, String> kept = new LinkedHashMap<>();
        headers.forEach((name, value) -> {
            if (names.contains(name.toLowerCase(Locale.ROOT))) {
                kept.put(name, value);
            }
        });
        return new OutboundRequest(method, uri, kept, body, contentType, maxBytes, waitForSlot, notAfter, errorBody);
    }

    /** Reads the body of an answer other than 200 too, for a source whose errors explain themselves there. */
    public OutboundRequest withErrorBody() {
        return new OutboundRequest(method, uri, headers, body, contentType, maxBytes, waitForSlot, notAfter, true);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof OutboundRequest(var otherMethod, var otherUri, var otherHeaders, var otherBody,
                var otherContentType, var otherMaxBytes, var otherWaitForSlot, var otherNotAfter, var otherErrorBody)
                && method.equals(otherMethod) && uri.equals(otherUri) && headers.equals(otherHeaders)
                && Arrays.equals(body, otherBody) && Objects.equals(contentType, otherContentType)
                && maxBytes == otherMaxBytes && waitForSlot == otherWaitForSlot && notAfter.equals(otherNotAfter)
                && errorBody == otherErrorBody;
    }

    @Override
    public int hashCode() {
        return Objects.hash(method, uri, headers, Arrays.hashCode(body), contentType, maxBytes, waitForSlot, notAfter, errorBody);
    }

    @Override
    public String toString() {
        return "OutboundRequest[" + method + " " + uri.getHost() + "]";
    }
}

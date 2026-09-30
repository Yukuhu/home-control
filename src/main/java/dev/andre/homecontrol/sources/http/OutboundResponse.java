package dev.andre.homecontrol.sources.http;

import java.net.URI;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * An answer from a {@link GuardedHttpClient}: any status, its content type, its body (empty for a redirect it
 * followed past, or an error body nobody asked for), its headers by lower-case name, first value only, and the URI
 * that answered, which after a redirect is not the one requested.
 */
public record OutboundResponse(int status, String contentType, byte[] body, Map<String, String> headers, URI uri) {

    public OutboundResponse {
        headers = Map.copyOf(headers);
    }

    public String header(String name) {
        return headers.get(name.toLowerCase(Locale.ROOT));
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof OutboundResponse(var otherStatus, var otherContentType, var otherBody, var otherHeaders,
                var otherUri) && status == otherStatus && Objects.equals(contentType, otherContentType)
                && Arrays.equals(body, otherBody) && headers.equals(otherHeaders) && uri.equals(otherUri);
    }

    @Override
    public int hashCode() {
        return Objects.hash(status, contentType, Arrays.hashCode(body), headers, uri);
    }

    @Override
    public String toString() {
        return "OutboundResponse[status=" + status + ", " + body.length + " bytes]";
    }
}

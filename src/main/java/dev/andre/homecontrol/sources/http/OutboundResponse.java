package dev.andre.homecontrol.sources.http;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * An answer from a {@link GuardedHttpClient}: any status, its content type, its body (empty for a redirect it
 * followed past) and its headers by lower-case name, first value only.
 */
public record OutboundResponse(int status, String contentType, byte[] body, Map<String, String> headers) {

    public OutboundResponse {
        headers = Map.copyOf(headers);
    }

    public String header(String name) {
        return headers.get(name.toLowerCase(Locale.ROOT));
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof OutboundResponse that && status == that.status
                && Objects.equals(contentType, that.contentType) && Arrays.equals(body, that.body)
                && headers.equals(that.headers);
    }

    @Override
    public int hashCode() {
        return Objects.hash(status, contentType, Arrays.hashCode(body), headers);
    }

    @Override
    public String toString() {
        return "OutboundResponse[status=" + status + ", " + body.length + " bytes]";
    }
}

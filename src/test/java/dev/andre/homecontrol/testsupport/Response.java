package dev.andre.homecontrol.testsupport;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A canned answer of {@link FakeHttpServer}. An empty body is sent as no body at all; a null content type sends no
 * Content-Type header.
 */
public record Response(int status, String contentType, Map<String, String> headers, byte[] body, Duration delay) {

    public static final String JSON = "application/json; charset=utf-8";

    public static Response of(int status, String contentType, byte[] body) {
        return new Response(status, contentType, Map.of(), body, Duration.ZERO);
    }

    public static Response of(int status, String contentType, String body) {
        return of(status, contentType, body.getBytes(StandardCharsets.UTF_8));
    }

    public static Response json(int status, String json) {
        return of(status, JSON, json);
    }

    /** A status alone: no Content-Type header and no body. */
    public static Response empty(int status) {
        return of(status, null, new byte[0]);
    }

    public Response withHeader(String name, String value) {
        Map<String, String> more = new LinkedHashMap<>(headers);
        more.put(name, value);
        return new Response(status, contentType, Map.copyOf(more), body, delay);
    }

    /** Waits this long before answering, on top of any delay the whole server has. */
    public Response withDelay(Duration wait) {
        return new Response(status, contentType, headers, body, wait);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Response that && status == that.status && Objects.equals(contentType, that.contentType)
                && headers.equals(that.headers) && Arrays.equals(body, that.body) && delay.equals(that.delay);
    }

    @Override
    public int hashCode() {
        return Objects.hash(status, contentType, headers, Arrays.hashCode(body), delay);
    }

    @Override
    public String toString() {
        return "Response[" + status + " " + contentType + ", " + body.length + " bytes, headers " + headers
                + ", delay " + delay + "]";
    }
}

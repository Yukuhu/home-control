package dev.andre.homecontrol.testsupport;

import com.sun.net.httpserver.HttpExchange;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * A request {@link FakeHttpServer} received. Header names are lower case; a header sent more than once is joined
 * with commas.
 */
public record Request(String method, URI uri, Map<String, String> query, Map<String, String> headers, String body) {

    /** The path as the client sent it, not decoded. */
    public String path() {
        return uri.getRawPath();
    }

    /** The query as the client sent it, or an empty string without one. */
    public String rawQuery() {
        return uri.getRawQuery() == null ? "" : uri.getRawQuery();
    }

    public String header(String name) {
        return headers.get(name.toLowerCase(Locale.ROOT));
    }

    /** Decodes {@code a=1&b=x%20y}. A name given more than once keeps its last value. */
    public static Map<String, String> decode(String rawQuery) {
        Map<String, String> values = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return values;
        }
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            String key = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            String value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            values.put(key, value);
        }
        return values;
    }

    static Request of(HttpExchange exchange) throws IOException {
        Map<String, String> headers = new TreeMap<>();
        exchange.getRequestHeaders().forEach((name, values) ->
                headers.put(name.toLowerCase(Locale.ROOT), String.join(",", values)));
        byte[] bytes = exchange.getRequestBody().readAllBytes();
        // A route handler reads exchange.getRequestBody() itself, so give it a fresh stream over the bytes we
        // already consumed; a null output stream keeps the original.
        exchange.setStreams(new ByteArrayInputStream(bytes), null);
        String body = new String(bytes, StandardCharsets.UTF_8);
        URI uri = exchange.getRequestURI();
        return new Request(exchange.getRequestMethod(), uri, decode(uri.getRawQuery()), headers, body);
    }
}

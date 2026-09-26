package dev.andre.homecontrol.sources.youtube;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.StringJoiner;

/** Plain HTTPS to Google: no redirects, timeouts, errors that never echo credentials. */
public class YouTubeHttp {

    // A cap, not a limit we expect to hit: a well-behaved Google answer never comes close, and a
    // misbehaving or hostile server (or a misconfigured oauth/api base URL) can't make us buffer an
    // unbounded amount of it into heap.
    static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    public record Response(int status, String contentType, byte[] body) {
        public boolean ok() {
            return status >= 200 && status < 300;
        }

        public String text() {
            return new String(body, StandardCharsets.UTF_8);
        }

        public JsonNode json() {
            try {
                return MAPPER.readTree(body);
            } catch (JacksonException _) {
                throw new YouTubeException(YouTubeException.Kind.BAD_RESPONSE, "Google sent an answer that is not JSON");
            }
        }
    }

    private final HttpClient http;
    private final Duration requestTimeout;

    public YouTubeHttp(YouTubeProperties properties) {
        this.http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(properties.connectTimeoutSeconds()))
                .build();
        this.requestTimeout = Duration.ofSeconds(properties.requestTimeoutSeconds());
    }

    public Response get(URI uri, Map<String, String> headers) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(requestTimeout).GET();
        headers.forEach(builder::header);
        return send(uri, builder);
    }

    public Response postForm(URI uri, Map<String, String> form, Map<String, String> headers) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(requestTimeout)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(form(form), StandardCharsets.UTF_8));
        headers.forEach(builder::header);
        return send(uri, builder);
    }

    private Response send(URI uri, HttpRequest.Builder builder) {
        HttpResponse<InputStream> response;
        try {
            response = http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException _) {
            throw new YouTubeException(YouTubeException.Kind.UNREACHABLE, "Could not reach " + uri.getHost());
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            throw new YouTubeException(YouTubeException.Kind.UNREACHABLE, "Interrupted while calling " + uri.getHost());
        }
        try (InputStream body = response.body()) {
            if (contentLengthExceeds(response, MAX_RESPONSE_BYTES)) {
                throw new YouTubeException(YouTubeException.Kind.BAD_RESPONSE, "Google sent an oversized response");
            }
            byte[] bytes = body.readNBytes(MAX_RESPONSE_BYTES + 1);
            if (bytes.length > MAX_RESPONSE_BYTES) {
                throw new YouTubeException(YouTubeException.Kind.BAD_RESPONSE, "Google sent an oversized response");
            }
            return new Response(response.statusCode(), response.headers().firstValue("Content-Type").orElse(""), bytes);
        } catch (IOException _) {
            throw new YouTubeException(YouTubeException.Kind.UNREACHABLE, "Could not reach " + uri.getHost());
        }
    }

    /** Rejects an oversized body before it is streamed, when the server is honest enough to declare its length. */
    private static boolean contentLengthExceeds(HttpResponse<?> response, int max) {
        return response.headers().firstValueAsLong("Content-Length").orElse(-1) > max;
    }

    /** {@code base + path + ?query}; null values are skipped; insertion order is kept. */
    public static URI uri(URI base, String path, Map<String, String> query) {
        String encoded = form(query);
        return URI.create(base.toString() + path + (encoded.isEmpty() ? "" : "?" + encoded));
    }

    public static String form(Map<String, String> values) {
        StringJoiner joined = new StringJoiner("&");
        values.forEach((key, value) -> {
            if (value != null) {
                joined.add(encode(key) + "=" + encode(value));
            }
        });
        return joined.toString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}

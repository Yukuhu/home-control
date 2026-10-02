package dev.andre.homecontrol.sources.youtube;

import dev.andre.homecontrol.config.Json;
import dev.andre.homecontrol.core.content.ContentSourceException;
import dev.andre.homecontrol.sources.http.GuardedHttpClient;
import dev.andre.homecontrol.sources.http.HttpUrls;
import dev.andre.homecontrol.sources.http.OutboundAddressPolicy;
import dev.andre.homecontrol.sources.http.OutboundRequest;
import dev.andre.homecontrol.sources.http.OutboundResponse;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;

/** Plain HTTPS to Google: no redirects, timeouts, errors that never echo credentials. */
public class YouTubeHttp implements AutoCloseable {

    // A cap, not a limit we expect to hit: a well-behaved Google answer never comes close, and a
    // misbehaving or hostile server (or a misconfigured oauth/api base URL) can't make us buffer an
    // unbounded amount of it into heap.
    static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    /** Thumbnails are proxied for the dashboard, many at once. */
    static final int MAX_CONCURRENT = 16;
    private static final HttpUrls.Rules GOOGLE_URLS = new HttpUrls.Rules(true, false, true, false, 0);

    /** As failures name the other side: "Could not reach Google at …". */
    private static final String NAME = "Google";
    private static final JsonMapper MAPPER = Json.MAPPER;

    /** An HTTP answer; compared by its body's content, and printed with the body's size only. */
    public record Response(int status, String contentType, byte[] body) {
        @Override
        public boolean equals(Object other) {
            return other instanceof Response(var otherStatus, var otherContentType, var otherBody)
                    && status == otherStatus && Objects.equals(contentType, otherContentType) && Arrays.equals(body, otherBody);
        }

        @Override
        public int hashCode() {
            return Objects.hash(status, contentType, Arrays.hashCode(body));
        }

        @Override
        public String toString() {
            return "Response[status=" + status + ", contentType=" + contentType + ", body="
                    + (body == null ? "none" : body.length + " bytes") + "]";
        }

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
                throw new YouTubeException(ContentSourceException.Kind.BAD_RESPONSE, "Google sent an answer that is not JSON");
            }
        }
    }

    private final GuardedHttpClient http;
    /** Thumbnails have slots of their own: a dashboard full of them must not hold up sign-in and API calls. */
    private final GuardedHttpClient thumbnails;

    public YouTubeHttp(YouTubeProperties properties) {
        // Every caller reads Google's error bodies (OAuth error codes, quota reasons), so every request asks for them.
        this.http = new GuardedHttpClient(new GuardedHttpClient.Profile(NAME, GuardedHttpClient.Redirects.NONE, 0,
                MAX_RESPONSE_BYTES, properties.connectTimeout(), properties.requestTimeout(), MAX_CONCURRENT,
                GOOGLE_URLS), new OutboundAddressPolicy(properties.allowLoopback()),
                failure -> new YouTubeException(failure.kind(), failure.describe(NAME)));
        this.thumbnails = new GuardedHttpClient(new GuardedHttpClient.Profile(NAME,
                GuardedHttpClient.Redirects.NONE, 0, MAX_RESPONSE_BYTES, properties.connectTimeout(),
                properties.requestTimeout(), MAX_CONCURRENT, GOOGLE_URLS),
                new OutboundAddressPolicy(properties.allowLoopback()),
                failure -> new YouTubeException(failure.kind(), failure.describe(NAME)));
    }

    public Response get(URI uri, Map<String, String> headers) {
        return send(withHeaders(OutboundRequest.get(uri), headers));
    }

    /** A video thumbnail, proxied for the dashboard. */
    public Response thumbnail(URI uri) {
        return send(thumbnails, withHeaders(OutboundRequest.get(uri), Map.of()));
    }

    public Response postForm(URI uri, Map<String, String> form, Map<String, String> headers) {
        return send(withHeaders(OutboundRequest.post(uri, form(form).getBytes(StandardCharsets.UTF_8),
                "application/x-www-form-urlencoded").header("Accept", "application/json"), headers));
    }

    private static OutboundRequest withHeaders(OutboundRequest request, Map<String, String> headers) {
        OutboundRequest with = request.withErrorBody();
        for (Map.Entry<String, String> header : headers.entrySet()) {
            with = with.header(header.getKey(), header.getValue());
        }
        return with;
    }

    private Response send(OutboundRequest request) {
        return send(http, request);
    }

    private static Response send(GuardedHttpClient client, OutboundRequest request) {
        OutboundResponse response = client.send(request);
        return new Response(response.status(), response.contentType() == null ? "" : response.contentType(),
                response.body());
    }

    @Override
    public void close() {
        http.close();
        thumbnails.close();
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

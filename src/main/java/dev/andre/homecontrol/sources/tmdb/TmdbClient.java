package dev.andre.homecontrol.sources.tmdb;

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

/**
 * The only class that speaks HTTP to TMDB. Never follows redirects (a redirect would replay the
 * bearer header elsewhere), caps bodies, and never puts a URL with its query into a message:
 * the API-key form carries the key in the query.
 */
public class TmdbClient {

    static final int MAX_BODY_BYTES = 2 * 1024 * 1024;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final TmdbProperties properties;
    private final HttpClient http;

    public TmdbClient(TmdbProperties properties) {
        this(properties, HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(properties.connectTimeoutSeconds()))
                .build());
    }

    public TmdbClient(TmdbProperties properties, HttpClient http) {
        this.properties = properties;
        this.http = http;
    }

    public JsonNode get(TmdbCredential credential, String path, Map<String, String> query) {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(credential, path, query))
                .GET()
                .timeout(Duration.ofSeconds(properties.requestTimeoutSeconds()))
                .header("Accept", "application/json");
        if (credential.kind() == TmdbCredential.Kind.BEARER) {
            request.header("Authorization", "Bearer " + credential.value());
        }
        HttpResponse<InputStream> response;
        byte[] body;
        try {
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = response.body()) {
                body = in.readNBytes(MAX_BODY_BYTES + 1);
            }
        } catch (IOException e) {
            throw new TmdbException(TmdbException.Kind.UNREACHABLE, unreachable(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TmdbException(TmdbException.Kind.UNREACHABLE, unreachable(), e);
        }
        int status = response.statusCode();
        if (status == 401 || status == 403) {
            throw new TmdbException(TmdbException.Kind.UNAUTHORIZED, "TMDB rejected the API key or read access token");
        }
        if (status == 404) {
            throw new TmdbException(TmdbException.Kind.NOT_FOUND, "TMDB does not know this title");
        }
        if (status == 429) {
            throw new TmdbException(TmdbException.Kind.RATE_LIMITED, "TMDB is limiting requests; try again in a moment");
        }
        if (status >= 500) {
            throw new TmdbException(TmdbException.Kind.SERVER_ERROR, "TMDB had a server error (HTTP " + status + ")");
        }
        if (status < 200 || status >= 300) {
            throw new TmdbException(TmdbException.Kind.BAD_RESPONSE, "TMDB answered HTTP " + status);
        }
        if (body.length > MAX_BODY_BYTES) {
            throw new TmdbException(TmdbException.Kind.BAD_RESPONSE, "TMDB answered with more data than expected");
        }
        JsonNode node;
        try {
            node = JSON.readTree(body);
        } catch (JacksonException _) {
            throw new TmdbException(TmdbException.Kind.BAD_RESPONSE, "TMDB answered with something that is not JSON");
        }
        if (node == null || !node.isObject()) {
            throw new TmdbException(TmdbException.Kind.BAD_RESPONSE, "TMDB answered with something unexpected");
        }
        return node;
    }

    private URI uri(TmdbCredential credential, String path, Map<String, String> query) {
        String base = properties.apiBaseUrl().toString().replaceAll("/+$", "");
        StringJoiner parameters = new StringJoiner("&");
        query.forEach((name, value) -> parameters.add(encode(name) + "=" + encode(value)));
        if (credential.kind() == TmdbCredential.Kind.API_KEY) {
            parameters.add("api_key=" + encode(credential.value()));
        }
        return URI.create(base + path + (parameters.length() == 0 ? "" : "?" + parameters));
    }

    private String unreachable() {
        return "Could not reach TMDB at " + properties.apiBaseUrl().getHost();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}

package dev.andre.homecontrol.sources.tmdb;

import dev.andre.homecontrol.config.Json;
import dev.andre.homecontrol.core.content.ContentSourceException;
import dev.andre.homecontrol.sources.http.GuardedHttpClient;
import dev.andre.homecontrol.sources.http.HttpUrls;
import dev.andre.homecontrol.sources.http.OutboundAddressPolicy;
import dev.andre.homecontrol.sources.http.OutboundRequest;
import dev.andre.homecontrol.sources.http.OutboundResponse;
import dev.andre.homecontrol.sources.http.Statuses;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.StringJoiner;

/**
 * The only class that speaks HTTP to TMDB. Never follows redirects (a redirect would replay the
 * bearer header elsewhere), caps bodies, and never puts a URL with its query into a message:
 * the API-key form carries the key in the query.
 */
public class TmdbClient implements AutoCloseable {

    static final int MAX_BODY_BYTES = 2 * 1024 * 1024;
    /** Rail refreshes, the setup page and pinned items share these. */
    private static final int MAX_CONCURRENT = 8;
    private static final HttpUrls.Rules API_URLS = new HttpUrls.Rules(true, false, true, false, 0);
    private static final JsonMapper JSON = Json.MAPPER;

    private final TmdbProperties properties;
    private final GuardedHttpClient http;

    public TmdbClient(TmdbProperties properties) {
        this.properties = properties;
        this.http = new GuardedHttpClient(new GuardedHttpClient.Profile("TMDB", GuardedHttpClient.Redirects.NONE, 0,
                MAX_BODY_BYTES, properties.connectTimeout(), properties.requestTimeout(), MAX_CONCURRENT, API_URLS),
                new OutboundAddressPolicy(properties.allowLoopback()),
                failure -> new TmdbException(failure.kind(), failure.describe("TMDB")));
    }

    public JsonNode get(TmdbCredential credential, String path, Map<String, String> query) {
        OutboundRequest request = OutboundRequest.get(uri(credential, path, query)).header("Accept", "application/json");
        if (credential.kind() == TmdbCredential.Kind.BEARER) {
            request = request.header("Authorization", "Bearer " + credential.value());
        }
        OutboundResponse response = http.send(request);
        int status = response.status();
        if (status != 200) {
            ContentSourceException.Kind kind = Statuses.kindOf(status);
            throw new TmdbException(kind, switch (kind) {
                case UNAUTHORIZED -> "TMDB rejected the API key or read access token";
                case NOT_FOUND -> "TMDB does not know this title";
                case RATE_LIMITED -> "TMDB is limiting requests; try again in a moment";
                case SERVER_ERROR -> "TMDB had a server error (HTTP " + status + ")";
                default -> "TMDB answered HTTP " + status;
            });
        }
        JsonNode node;
        try {
            node = JSON.readTree(response.body());
        } catch (JacksonException _) {
            throw new TmdbException(ContentSourceException.Kind.BAD_RESPONSE, "TMDB answered with something that is not JSON");
        }
        if (node == null || !node.isObject()) {
            throw new TmdbException(ContentSourceException.Kind.BAD_RESPONSE, "TMDB answered with something unexpected");
        }
        return node;
    }

    @Override
    public void close() {
        http.close();
    }

    private URI uri(TmdbCredential credential, String path, Map<String, String> query) {
        String base = withoutTrailingSlashes(properties.apiBaseUrl().toString());
        StringJoiner parameters = new StringJoiner("&");
        query.forEach((name, value) -> parameters.add(encode(name) + "=" + encode(value)));
        if (credential.kind() == TmdbCredential.Kind.API_KEY) {
            parameters.add("api_key=" + encode(credential.value()));
        }
        return URI.create(base + path + (parameters.length() == 0 ? "" : "?" + parameters));
    }

    private static String withoutTrailingSlashes(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(0, end);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}

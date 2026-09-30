package dev.andre.homecontrol.sources.jellyfin;

import dev.andre.homecontrol.core.content.ContentSourceException;
import dev.andre.homecontrol.sources.http.GuardedHttpClient;
import dev.andre.homecontrol.sources.http.HttpUrls;
import dev.andre.homecontrol.sources.http.OutboundAddressPolicy;
import dev.andre.homecontrol.sources.http.OutboundFailure;
import dev.andre.homecontrol.sources.http.OutboundRequest;
import dev.andre.homecontrol.sources.http.OutboundResponse;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.MissingNode;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The only class that speaks HTTP to Jellyfin (spec §7: only sources speak content APIs). */
public class JellyfinClient implements AutoCloseable {

    private static final String APPLICATION_JSON = "application/json";

    static final String CLIENT_NAME = "Home Control";
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9-]{1,64}");
    private static final Pattern SERVER_VERSION_PATTERN = Pattern.compile("^(\\d+)\\.(\\d+)");
    /** A generous cap on any Jellyfin JSON answer; a well-behaved server never comes close. */
    static final int MAX_JSON_BYTES = 2 * 1024 * 1024;
    private static final HttpUrls.Rules SERVER_URLS = new HttpUrls.Rules(false, false, true, false, 0);
    /** A generous cap on one artwork image; a well-behaved server never comes close. */
    static final int MAX_IMAGE_BYTES = 10 * 1024 * 1024;
    /** Artwork and session pages load many at once from the LAN server. */
    private static final int MAX_CONCURRENT = 16;
    /** Raster types only: an SVG served from our own origin could carry a script. */
    private static final Set<String> ALLOWED_IMAGE_TYPES = Set.of("image/jpeg", "image/png", "image/webp", "image/gif");

    private final GuardedHttpClient http;
    private final JellyfinProperties properties;
    private final String version;
    private final JsonMapper mapper = JsonMapper.builder().build();

    public JellyfinClient(JellyfinProperties properties) {
        this(properties, Optional.ofNullable(JellyfinClient.class.getPackage().getImplementationVersion()).orElse("0.0.0"));
    }

    JellyfinClient(JellyfinProperties properties, String version) {
        this.properties = properties;
        this.version = version;
        // Jellyfin often runs on this machine, so loopback is allowed. Redirects are refused: they would replay the
        // Authorization header.
        this.http = new GuardedHttpClient(new GuardedHttpClient.Profile("Jellyfin", GuardedHttpClient.Redirects.NONE,
                0, MAX_JSON_BYTES, properties.connectTimeout(), properties.requestTimeout(), MAX_CONCURRENT, SERVER_URLS),
                new OutboundAddressPolicy(true), JellyfinClient::failure);
    }

    private static JellyfinException failure(OutboundFailure failure) {
        String message = failure.describe("Jellyfin");
        return new JellyfinException(failure.kind(), failure.kind() == ContentSourceException.Kind.UNREACHABLE
                ? message + ". Check the address and that Home Control can reach it." : message);
    }

    @Override
    public void close() {
        http.close();
    }

    /** http(s) scheme, a host, no user info, query or fragment; trailing slashes removed. */
    public static URI normalizeServerUrl(String raw) {
        String trimmed = raw == null ? "" : raw.strip();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        try {
            return HttpUrls.parse(trimmed, SERVER_URLS);
        } catch (HttpUrls.InvalidUrlException _) {
            throw new JellyfinException(ContentSourceException.Kind.INVALID_INPUT,
                    "Enter the Jellyfin address as http://host:8096 (or https://…)");
        }
    }

    /** Jellyfin ids are GUIDs (with or without dashes); anything else never becomes a path segment. */
    public static String id(String value) {
        if (value == null || !ID.matcher(value).matches()) {
            throw new IllegalArgumentException("Not a Jellyfin id");
        }
        return value;
    }

    public JsonNode publicInfo(URI serverUrl) {
        JsonNode info;
        try {
            info = send(serverUrl, signed(OutboundRequest.get(uri(serverUrl, "/System/Info/Public", Map.of())), null, null));
        } catch (JellyfinException e) {
            if (e.kind() == ContentSourceException.Kind.NOT_FOUND || e.kind() == ContentSourceException.Kind.BAD_RESPONSE) {
                throw notJellyfin(serverUrl);
            }
            throw e;
        }
        String product = info.path("ProductName").asString("Jellyfin Server");
        if (!info.isObject() || info.path("Id").asString("").isBlank() || !product.contains("Jellyfin")) {
            throw notJellyfin(serverUrl);
        }
        String serverVersion = info.path("Version").asString("");
        Matcher matcher = SERVER_VERSION_PATTERN.matcher(serverVersion);
        if (!matcher.find() || Integer.parseInt(matcher.group(1)) < 10
                || (Integer.parseInt(matcher.group(1)) == 10 && Integer.parseInt(matcher.group(2)) < 9)) {
            throw new JellyfinException(ContentSourceException.Kind.BAD_RESPONSE,
                    "Jellyfin " + serverVersion + " is too old; Home Control needs Jellyfin 10.9 or newer");
        }
        return info;
    }

    public JsonNode authenticateByName(URI serverUrl, String deviceId, String userName, String password) {
        ObjectNode body = mapper.createObjectNode();
        body.put("Username", userName);
        body.put("Pw", password);
        try {
            return send(serverUrl, signed(OutboundRequest.post(uri(serverUrl, "/Users/AuthenticateByName", Map.of()),
                    mapper.writeValueAsBytes(body), APPLICATION_JSON), deviceId, null));
        } catch (JellyfinException e) {
            if (e.kind() == ContentSourceException.Kind.UNAUTHORIZED) {
                throw new JellyfinException(ContentSourceException.Kind.UNAUTHORIZED, "Jellyfin rejected the user name or password");
            }
            throw e;
        }
    }

    public JsonNode get(JellyfinConnection connection, String path, Map<String, String> query) {
        return send(connection.serverUrl(), signed(OutboundRequest.get(uri(connection.serverUrl(), path, query)),
                connection.deviceId(), connection.token()));
    }

    /** {@code body} may be null for commands such as {@code /Sessions/{id}/Playing}. */
    public JsonNode post(JellyfinConnection connection, String path, Map<String, String> query, JsonNode body) {
        OutboundRequest request = body == null
                ? OutboundRequest.post(uri(connection.serverUrl(), path, query), new byte[0], null)
                : OutboundRequest.post(uri(connection.serverUrl(), path, query), mapper.writeValueAsBytes(body), APPLICATION_JSON);
        return send(connection.serverUrl(), signed(request, connection.deviceId(), connection.token()));
    }

    String authorization(String deviceId, String token) {
        StringBuilder header = new StringBuilder("MediaBrowser ")
                .append("Client=\"").append(encode(CLIENT_NAME)).append("\", ")
                .append("Device=\"").append(encode(CLIENT_NAME)).append("\", ")
                .append("DeviceId=\"").append(encode(deviceId == null ? "home-control" : deviceId)).append("\", ")
                .append("Version=\"").append(encode(version)).append('"');
        if (token != null) {
            header.append(", Token=\"").append(encode(token)).append('"');
        }
        return header.toString();
    }

    /** An image as served; compared by its bytes' content, and printed with their count only. */
    public record Image(String contentType, byte[] bytes) {
        @Override
        public boolean equals(Object other) {
            return other instanceof Image(var otherContentType, var otherBytes)
                    && Objects.equals(contentType, otherContentType) && Arrays.equals(bytes, otherBytes);
        }

        @Override
        public int hashCode() {
            return 31 * Objects.hashCode(contentType) + Arrays.hashCode(bytes);
        }

        @Override
        public String toString() {
            return "Image[contentType=" + contentType + ", bytes=" + (bytes == null ? "none" : bytes.length + " bytes") + "]";
        }
    }

    /** Jellyfin's item image endpoint is anonymous; no credential is sent, so none can leak. */
    public Optional<Image> image(URI serverUrl, String itemId, String type, String tag, int maxWidth) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("maxWidth", String.valueOf(maxWidth));
        query.put("quality", "90");
        if (tag != null) {
            query.put("tag", tag);
        }
        OutboundResponse response = http.send(OutboundRequest.get(uri(serverUrl, "/Items/" + id(itemId) + "/Images/" + type, query))
                .header("Accept", "image/*").limitedTo(MAX_IMAGE_BYTES));
        if (response.status() == 404) {
            return Optional.empty();
        }
        String contentType = response.contentType() == null ? "" : response.contentType();
        String bareType = contentType.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
        if (response.status() != 200 || !ALLOWED_IMAGE_TYPES.contains(bareType)) {
            throw new JellyfinException(ContentSourceException.Kind.BAD_RESPONSE, "Jellyfin at " + serverUrl + " sent no image");
        }
        return Optional.of(new Image(contentType, response.body()));
    }

    private static String queryString(Map<String, String> query) {
        StringJoiner joined = new StringJoiner("&", "?", "");
        joined.setEmptyValue("");
        query.forEach((key, value) -> {
            if (value != null) {
                joined.add(encode(key) + "=" + encode(value));
            }
        });
        return joined.toString();
    }

    private static URI uri(URI serverUrl, String path, Map<String, String> query) {
        return URI.create(serverUrl + path + queryString(query));
    }

    private OutboundRequest signed(OutboundRequest request, String deviceId, String token) {
        return request.header("Accept", APPLICATION_JSON).header("Authorization", authorization(deviceId, token));
    }

    private JsonNode send(URI serverUrl, OutboundRequest request) {
        OutboundResponse response = http.send(request);
        requireSuccess(serverUrl, response.status());
        byte[] bytes = response.body();
        return bytes.length == 0 ? MissingNode.getInstance() : parse(serverUrl, bytes);
    }

    /** Redirects, rejected credentials, unknown paths and every other 4xx/5xx answer end the call. */
    private static void requireSuccess(URI serverUrl, int status) {
        if (status >= 300 && status < 400) {
            throw new JellyfinException(ContentSourceException.Kind.BAD_RESPONSE,
                    "Jellyfin at " + serverUrl + " redirected elsewhere; enter the final server address");
        }
        if (status == 401 || status == 403) {
            throw new JellyfinException(ContentSourceException.Kind.UNAUTHORIZED,
                    "Jellyfin rejected the stored credentials; reconnect Jellyfin on the setup page");
        }
        if (status == 404) {
            throw new JellyfinException(ContentSourceException.Kind.NOT_FOUND, "Jellyfin at " + serverUrl + " does not know that");
        }
        if (status >= 400) {
            throw new JellyfinException(ContentSourceException.Kind.SERVER_ERROR, "Jellyfin at " + serverUrl + " answered HTTP " + status);
        }
    }

    private JsonNode parse(URI serverUrl, byte[] bytes) {
        try {
            return mapper.readTree(bytes);
        } catch (JacksonException _) {
            throw new JellyfinException(ContentSourceException.Kind.BAD_RESPONSE, "Jellyfin at " + serverUrl + " sent an unreadable answer");
        }
    }

    private static JellyfinException notJellyfin(URI serverUrl) {
        return new JellyfinException(ContentSourceException.Kind.BAD_RESPONSE, serverUrl + " answered, but it is not a Jellyfin server");
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}

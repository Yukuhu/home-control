package dev.andre.homecontrol.sources.jellyfin;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.MissingNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The only class that speaks HTTP to Jellyfin (spec §7: only sources speak content APIs). */
public class JellyfinClient {

    static final String CLIENT_NAME = "Home Control";
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9-]{1,64}");
    private static final Pattern SERVER_VERSION_PATTERN = Pattern.compile("^(\\d+)\\.(\\d+)");
    /** A generous cap on any Jellyfin JSON answer; a well-behaved server never comes close. */
    static final int MAX_JSON_BYTES = 2 * 1024 * 1024;
    /** A generous cap on one artwork image; a well-behaved server never comes close. */
    static final int MAX_IMAGE_BYTES = 10 * 1024 * 1024;
    /** Raster types only: an SVG served from our own origin could carry a script. */
    private static final Set<String> ALLOWED_IMAGE_TYPES = Set.of("image/jpeg", "image/png", "image/webp", "image/gif");

    private final HttpClient http;
    private final JellyfinProperties properties;
    private final String version;
    private final JsonMapper mapper = JsonMapper.builder().build();

    public JellyfinClient(JellyfinProperties properties) {
        this(properties, Optional.ofNullable(JellyfinClient.class.getPackage().getImplementationVersion()).orElse("0.0.0"));
    }

    JellyfinClient(JellyfinProperties properties, String version) {
        this.properties = properties;
        this.version = version;
        this.http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER) // a redirect would replay the Authorization header
                .connectTimeout(Duration.ofSeconds(properties.connectTimeoutSeconds()))
                .build();
    }

    /** http(s) scheme, a host, no user info, query or fragment; trailing slashes removed. */
    public static URI normalizeServerUrl(String raw) {
        String trimmed = raw == null ? "" : raw.strip();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        try {
            URI uri = new URI(trimmed);
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                    || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                    || uri.getRawFragment() != null) {
                throw new URISyntaxException(trimmed, "not a plain http(s) URL");
            }
            return uri;
        } catch (URISyntaxException e) {
            throw new JellyfinException(JellyfinException.Kind.INVALID_INPUT,
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
            info = send(serverUrl, request(serverUrl, "/System/Info/Public", Map.of(), null, null).GET());
        } catch (JellyfinException e) {
            if (e.kind() == JellyfinException.Kind.NOT_FOUND || e.kind() == JellyfinException.Kind.BAD_RESPONSE) {
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
            throw new JellyfinException(JellyfinException.Kind.UNSUPPORTED_VERSION,
                    "Jellyfin " + serverVersion + " is too old; Home Control needs Jellyfin 10.9 or newer");
        }
        return info;
    }

    public JsonNode authenticateByName(URI serverUrl, String deviceId, String userName, String password) {
        ObjectNode body = mapper.createObjectNode();
        body.put("Username", userName);
        body.put("Pw", password);
        try {
            return send(serverUrl, request(serverUrl, "/Users/AuthenticateByName", Map.of(), deviceId, null)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(body))));
        } catch (JellyfinException e) {
            if (e.kind() == JellyfinException.Kind.UNAUTHORIZED) {
                throw new JellyfinException(JellyfinException.Kind.UNAUTHORIZED, "Jellyfin rejected the user name or password");
            }
            throw e;
        }
    }

    public JsonNode get(JellyfinConnection connection, String path, Map<String, String> query) {
        return send(connection.serverUrl(),
                request(connection.serverUrl(), path, query, connection.deviceId(), connection.token()).GET());
    }

    /** {@code body} may be null for commands such as {@code /Sessions/{id}/Playing}. */
    public JsonNode post(JellyfinConnection connection, String path, Map<String, String> query, JsonNode body) {
        HttpRequest.Builder builder = request(connection.serverUrl(), path, query, connection.deviceId(), connection.token());
        if (body == null) {
            builder.POST(HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(body)));
        }
        return send(connection.serverUrl(), builder);
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

    public record Image(String contentType, byte[] bytes) {
    }

    /** Jellyfin's item image endpoint is anonymous; no credential is sent, so none can leak. */
    public Optional<Image> image(URI serverUrl, String itemId, String type, String tag, int maxWidth) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("maxWidth", String.valueOf(maxWidth));
        query.put("quality", "90");
        if (tag != null) {
            query.put("tag", tag);
        }
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(serverUrl + "/Items/" + id(itemId) + "/Images/" + type + queryString(query)))
                .timeout(Duration.ofSeconds(properties.requestTimeoutSeconds()))
                .header("Accept", "image/*")
                .GET()
                .build();
        HttpResponse<InputStream> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (HttpConnectTimeoutException e) {
            throw unreachable(serverUrl, "connection timed out");
        } catch (HttpTimeoutException e) {
            throw unreachable(serverUrl, "no answer in time");
        } catch (ConnectException e) {
            throw unreachable(serverUrl, e.getCause() instanceof UnresolvedAddressException ? "unknown host" : "connection refused");
        } catch (IOException e) {
            throw unreachable(serverUrl, e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unreachable(serverUrl, "interrupted");
        }
        try (InputStream body = response.body()) {
            if (response.statusCode() == 404) {
                return Optional.empty();
            }
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            String bareType = contentType.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
            if (response.statusCode() != 200 || !ALLOWED_IMAGE_TYPES.contains(bareType)
                    || contentLengthExceeds(response, MAX_IMAGE_BYTES)) {
                throw new JellyfinException(JellyfinException.Kind.BAD_RESPONSE, "Jellyfin at " + serverUrl + " sent no image");
            }
            byte[] bytes = body.readNBytes(MAX_IMAGE_BYTES + 1);
            if (bytes.length > MAX_IMAGE_BYTES) {
                throw new JellyfinException(JellyfinException.Kind.BAD_RESPONSE, "Jellyfin at " + serverUrl + " sent an oversized image");
            }
            return Optional.of(new Image(contentType, bytes));
        } catch (IOException e) {
            throw unreachable(serverUrl, e.getClass().getSimpleName());
        }
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

    private HttpRequest.Builder request(URI serverUrl, String path, Map<String, String> query, String deviceId, String token) {
        return HttpRequest.newBuilder(URI.create(serverUrl + path + queryString(query)))
                .timeout(Duration.ofSeconds(properties.requestTimeoutSeconds()))
                .header("Accept", "application/json")
                .header("Authorization", authorization(deviceId, token));
    }

    private JsonNode send(URI serverUrl, HttpRequest.Builder builder) {
        HttpResponse<InputStream> response;
        try {
            response = http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (HttpConnectTimeoutException e) {
            throw unreachable(serverUrl, "connection timed out");
        } catch (HttpTimeoutException e) {
            throw unreachable(serverUrl, "no answer in time");
        } catch (ConnectException e) {
            throw unreachable(serverUrl, e.getCause() instanceof UnresolvedAddressException ? "unknown host" : "connection refused");
        } catch (IOException e) {
            throw unreachable(serverUrl, e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unreachable(serverUrl, "interrupted");
        }
        int status = response.statusCode();
        try (InputStream body = response.body()) {
            if (status >= 300 && status < 400) {
                throw new JellyfinException(JellyfinException.Kind.BAD_RESPONSE,
                        "Jellyfin at " + serverUrl + " redirected elsewhere; enter the final server address");
            }
            if (status == 401 || status == 403) {
                throw new JellyfinException(JellyfinException.Kind.UNAUTHORIZED,
                        "Jellyfin rejected the stored credentials; reconnect Jellyfin on the setup page");
            }
            if (status == 404) {
                throw new JellyfinException(JellyfinException.Kind.NOT_FOUND, "Jellyfin at " + serverUrl + " does not know that");
            }
            if (status >= 400) {
                throw new JellyfinException(JellyfinException.Kind.SERVER_ERROR, "Jellyfin at " + serverUrl + " answered HTTP " + status);
            }
            // A cap, not a limit we expect to hit: a well-behaved Jellyfin answer never comes close, and a
            // misbehaving or hostile server can't make us buffer an unbounded amount of it into heap.
            if (contentLengthExceeds(response, MAX_JSON_BYTES)) {
                throw new JellyfinException(JellyfinException.Kind.BAD_RESPONSE, "Jellyfin at " + serverUrl + " sent an oversized response");
            }
            byte[] bytes = body.readNBytes(MAX_JSON_BYTES + 1);
            if (bytes.length > MAX_JSON_BYTES) {
                throw new JellyfinException(JellyfinException.Kind.BAD_RESPONSE, "Jellyfin at " + serverUrl + " sent an oversized response");
            }
            if (bytes.length == 0) {
                return MissingNode.getInstance();
            }
            try {
                return mapper.readTree(bytes);
            } catch (JacksonException e) {
                throw new JellyfinException(JellyfinException.Kind.BAD_RESPONSE, "Jellyfin at " + serverUrl + " sent an unreadable answer");
            }
        } catch (IOException e) {
            throw unreachable(serverUrl, e.getClass().getSimpleName());
        }
    }

    /** Rejects an oversized body before it is streamed, when the server is honest enough to declare its length. */
    private static boolean contentLengthExceeds(HttpResponse<?> response, int max) {
        return response.headers().firstValueAsLong("Content-Length").orElse(-1) > max;
    }

    private static JellyfinException unreachable(URI serverUrl, String reason) {
        return new JellyfinException(JellyfinException.Kind.UNREACHABLE, "Could not reach Jellyfin at " + serverUrl
                + " (" + reason + "). Check the address and that Home Control can reach it.");
    }

    private static JellyfinException notJellyfin(URI serverUrl) {
        return new JellyfinException(JellyfinException.Kind.NOT_JELLYFIN, serverUrl + " answered, but it is not a Jellyfin server");
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}

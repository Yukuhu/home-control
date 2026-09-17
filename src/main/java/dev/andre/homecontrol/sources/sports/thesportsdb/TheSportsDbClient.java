package dev.andre.homecontrol.sources.sports.thesportsdb;

import dev.andre.homecontrol.sources.sports.SportsProperties;
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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.regex.Pattern;

/**
 * The only class that speaks HTTP to TheSportsDB. Never follows redirects, caps bodies, and never
 * puts the URL or the key into a message: the key is part of the path in every request.
 */
public class TheSportsDbClient {

    static final int MAX_BODY_BYTES = 2 * 1024 * 1024;
    private static final Pattern KEY = Pattern.compile("^[A-Za-z0-9]{1,64}$");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final SportsProperties.TheSportsDb properties;
    private final HttpClient http;

    public TheSportsDbClient(SportsProperties.TheSportsDb properties) {
        this(properties, HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(properties.connectTimeoutSeconds()))
                .build());
    }

    public TheSportsDbClient(SportsProperties.TheSportsDb properties, HttpClient http) {
        this.properties = properties;
        this.http = http;
    }

    public JsonNode get(String key, String endpoint, Map<String, String> query) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new TheSportsDbException(TheSportsDbException.Kind.UNAUTHORIZED, "That does not look like a TheSportsDB API key");
        }
        HttpRequest request = HttpRequest.newBuilder(uri(key, endpoint, query))
                .GET()
                .timeout(Duration.ofSeconds(properties.requestTimeoutSeconds()))
                .header("Accept", "application/json")
                .header("User-Agent", "HomeControl")
                .build();
        HttpResponse<InputStream> response;
        byte[] body;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = response.body()) {
                body = in.readNBytes(MAX_BODY_BYTES + 1);
            }
        } catch (IOException e) {
            throw new TheSportsDbException(TheSportsDbException.Kind.UNREACHABLE, "Could not reach TheSportsDB", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TheSportsDbException(TheSportsDbException.Kind.UNREACHABLE, "Could not reach TheSportsDB", e);
        }
        int status = response.statusCode();
        if (body.length > MAX_BODY_BYTES) {
            throw new TheSportsDbException(TheSportsDbException.Kind.BAD_RESPONSE, "TheSportsDB answered with more data than expected");
        }
        boolean mentionsApiKey = new String(body, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT).contains("api key");
        if (status == 401 || status == 403 || ((status == 400 || status == 404) && mentionsApiKey)) {
            throw new TheSportsDbException(TheSportsDbException.Kind.UNAUTHORIZED, "TheSportsDB rejected the API key");
        }
        if (status == 429) {
            throw new TheSportsDbException(TheSportsDbException.Kind.RATE_LIMITED, "TheSportsDB is limiting requests; try again in a minute");
        }
        if (status >= 500) {
            throw new TheSportsDbException(TheSportsDbException.Kind.SERVER_ERROR, "TheSportsDB had a server error (HTTP " + status + ")");
        }
        if (status < 200 || status >= 300) {
            throw new TheSportsDbException(TheSportsDbException.Kind.BAD_RESPONSE, "TheSportsDB answered HTTP " + status);
        }
        JsonNode node;
        try {
            node = JSON.readTree(body);
        } catch (JacksonException e) {
            throw new TheSportsDbException(TheSportsDbException.Kind.BAD_RESPONSE, "TheSportsDB answered with something that is not JSON");
        }
        if (node == null || !node.isObject()) {
            throw new TheSportsDbException(TheSportsDbException.Kind.BAD_RESPONSE, "TheSportsDB answered with something unexpected");
        }
        return node;
    }

    public Optional<League> lookupLeague(String key, String leagueId) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("id", leagueId);
        JsonNode leagues = get(key, "lookupleague.php", query).path("leagues");
        if (!leagues.isArray() || leagues.isEmpty()) {
            return Optional.empty();
        }
        return League.of(leagues.get(0));
    }

    public List<League> searchLeagues(String key, String country, String sport) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("c", country);
        if (sport != null && !sport.isBlank()) {
            query.put("s", sport);
        }
        JsonNode countries = get(key, "search_all_leagues.php", query).path("countries");
        List<League> result = new ArrayList<>();
        if (countries.isArray()) {
            for (JsonNode node : countries) {
                if (result.size() >= 50) {
                    break;
                }
                League.of(node).ifPresent(result::add);
            }
        }
        return List.copyOf(result);
    }

    public List<JsonNode> eventsDay(String key, LocalDate utcDate, String leagueId) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("d", utcDate.toString());
        query.put("l", leagueId);
        JsonNode events = get(key, "eventsday.php", query).path("events");
        List<JsonNode> result = new ArrayList<>();
        if (events.isArray()) {
            events.forEach(result::add);
        }
        return List.copyOf(result);
    }

    private URI uri(String key, String endpoint, Map<String, String> query) {
        String base = properties.apiBaseUrl().toString().replaceAll("/+$", "");
        StringJoiner parameters = new StringJoiner("&");
        query.forEach((name, value) -> parameters.add(encode(name) + "=" + encode(value)));
        String queryString = parameters.length() == 0 ? "" : "?" + parameters;
        return URI.create(base + "/" + key + "/" + endpoint + queryString);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}

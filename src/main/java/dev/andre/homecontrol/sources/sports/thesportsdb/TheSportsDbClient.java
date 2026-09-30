package dev.andre.homecontrol.sources.sports.thesportsdb;

import dev.andre.homecontrol.config.Json;
import dev.andre.homecontrol.core.content.ContentSourceException;
import dev.andre.homecontrol.sources.http.GuardedHttpClient;
import dev.andre.homecontrol.sources.http.HttpUrls;
import dev.andre.homecontrol.sources.http.OutboundAddressPolicy;
import dev.andre.homecontrol.sources.http.OutboundRequest;
import dev.andre.homecontrol.sources.http.OutboundResponse;
import dev.andre.homecontrol.sources.http.Statuses;
import dev.andre.homecontrol.sources.sports.settings.SportsProperties;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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
public class TheSportsDbClient implements AutoCloseable {

    static final int MAX_BODY_BYTES = 2 * 1024 * 1024;
    /** Schedule refreshes and the setup page share these. */
    private static final int MAX_CONCURRENT = 8;
    private static final HttpUrls.Rules API_URLS = new HttpUrls.Rules(true, false, true, false, 0);
    private static final Pattern KEY = Pattern.compile("^[A-Za-z0-9]{1,64}$");
    private static final JsonMapper JSON = Json.MAPPER;

    private final SportsProperties.TheSportsDb properties;
    private final GuardedHttpClient http;

    public TheSportsDbClient(SportsProperties.TheSportsDb properties) {
        this.properties = properties;
        this.http = new GuardedHttpClient(new GuardedHttpClient.Profile("TheSportsDB",
                GuardedHttpClient.Redirects.NONE, 0, MAX_BODY_BYTES, properties.connectTimeout(),
                properties.requestTimeout(), MAX_CONCURRENT, API_URLS),
                new OutboundAddressPolicy(properties.allowLoopback()),
                failure -> new TheSportsDbException(failure.kind(), failure.describe("TheSportsDB")));
    }

    public JsonNode get(String key, String endpoint, Map<String, String> query) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new TheSportsDbException(ContentSourceException.Kind.UNAUTHORIZED, "That does not look like a TheSportsDB API key");
        }
        // The error body is read: TheSportsDB says "api key" in it when it refuses the key with a 400 or 404.
        OutboundResponse response = http.send(OutboundRequest.get(uri(key, endpoint, query))
                .header("Accept", "application/json").header("User-Agent", "HomeControl").withErrorBody());
        int status = response.status();
        boolean mentionsApiKey = new String(response.body(), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT).contains("api key");
        if (status == 401 || status == 403 || ((status == 400 || status == 404) && mentionsApiKey)) {
            throw new TheSportsDbException(ContentSourceException.Kind.UNAUTHORIZED, "TheSportsDB rejected the API key");
        }
        if (status != 200) {
            ContentSourceException.Kind kind = Statuses.kindOf(status);
            throw new TheSportsDbException(kind, switch (kind) {
                case RATE_LIMITED -> "TheSportsDB is limiting requests; try again in a minute";
                case SERVER_ERROR -> "TheSportsDB had a server error (HTTP " + status + ")";
                default -> "TheSportsDB answered HTTP " + status;
            });
        }
        JsonNode node;
        try {
            node = JSON.readTree(response.body());
        } catch (JacksonException _) {
            throw new TheSportsDbException(ContentSourceException.Kind.BAD_RESPONSE, "TheSportsDB answered with something that is not JSON");
        }
        if (node == null || !node.isObject()) {
            throw new TheSportsDbException(ContentSourceException.Kind.BAD_RESPONSE, "TheSportsDB answered with something unexpected");
        }
        return node;
    }

    @Override
    public void close() {
        http.close();
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
        String base = withoutTrailingSlashes(properties.apiBaseUrl().toString());
        StringJoiner parameters = new StringJoiner("&");
        query.forEach((name, value) -> parameters.add(encode(name) + "=" + encode(value)));
        String queryString = parameters.length() == 0 ? "" : "?" + parameters;
        return URI.create(base + "/" + key + "/" + endpoint + queryString);
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

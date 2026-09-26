package dev.andre.homecontrol.sources.sports.thesportsdb;

import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Optional;
import java.util.regex.Pattern;

/** A TheSportsDB league/competition, as returned by {@code lookupleague.php} and {@code search_all_leagues.php}. */
public record League(String id, String name, String sport, String country, URI badge) {

    private static final Pattern LEAGUE_ID_PATTERN = Pattern.compile("^[0-9]{1,9}$");
    private static final int MAX_NAME = 120;

    public static Optional<League> of(JsonNode node) {
        String id = node.path("idLeague").asString("");
        if (!LEAGUE_ID_PATTERN.matcher(id).matches()) {
            return Optional.empty();
        }
        String name = node.path("strLeague").asString("").strip();
        if (name.isEmpty()) {
            return Optional.empty();
        }
        if (name.length() > MAX_NAME) {
            name = name.substring(0, MAX_NAME);
        }
        String sport = shortField(node.path("strSport"));
        String country = shortField(node.path("strCountry"));
        URI badge = absoluteHttps(node.path("strBadge"));
        return Optional.of(new League(id, name, sport, country, badge));
    }

    private static String shortField(JsonNode node) {
        if (!node.isString()) {
            return null;
        }
        String value = node.asString().strip();
        return value.isEmpty() ? null : value;
    }

    private static URI absoluteHttps(JsonNode node) {
        if (!node.isString()) {
            return null;
        }
        String raw = node.asString();
        if (!raw.startsWith("https://")) {
            return null;
        }
        try {
            return new URI(raw);
        } catch (URISyntaxException _) {
            return null;
        }
    }
}

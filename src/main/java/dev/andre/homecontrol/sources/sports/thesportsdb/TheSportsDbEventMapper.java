package dev.andre.homecontrol.sources.sports.thesportsdb;

import dev.andre.homecontrol.sources.sports.SportsEvent;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/** Maps a TheSportsDB {@code eventsday.php} element to a {@link SportsEvent}, or nothing when it does not qualify. */
public final class TheSportsDbEventMapper {

    private static final Pattern ID = Pattern.compile("^[0-9]{1,12}$");
    private static final Pattern OFFSET = Pattern.compile("[+-]\\d{2}:\\d{2}$");
    private static final int MAX_TITLE = 200;

    private static final Set<String> CANCELLED_LIKE = Set.of(
            "canc", "cancelled", "pst", "postponed", "abd", "abandoned", "awd", "awarded", "susp", "suspended");
    private static final Set<String> FINISHED_LIKE = Set.of(
            "ft", "aet", "pen", "aot", "match finished", "finished", "after extra time", "after penalties",
            "after over time");
    private static final Set<String> LIVE_LIKE = Set.of(
            "1h", "ht", "2h", "et", "bt", "p", "live", "in progress", "q1", "q2", "q3", "q4", "ot", "break time");

    private TheSportsDbEventMapper() {
    }

    public static Optional<SportsEvent> toEvent(JsonNode event, String leagueId, URI badge, ZoneId zone,
                                                Function<String, Duration> durations) {
        String idEvent = stringOf(event.path("idEvent"));
        if (idEvent == null || !ID.matcher(idEvent).matches()) {
            return Optional.empty();
        }
        if (!leagueId.equals(stringOf(event.path("idLeague")))) {
            return Optional.empty();
        }
        if ("yes".equalsIgnoreCase(event.path("strPostponed").asString(""))) {
            return Optional.empty();
        }
        String status = event.path("strStatus").asString("").strip().toLowerCase(Locale.ROOT);
        if (CANCELLED_LIKE.contains(status)) {
            return Optional.empty();
        }
        SportsEvent.Status mappedStatus = FINISHED_LIKE.contains(status) ? SportsEvent.Status.FINISHED
                : LIVE_LIKE.contains(status) ? SportsEvent.Status.LIVE : SportsEvent.Status.SCHEDULED;

        String title = title(event);
        if (title == null) {
            return Optional.empty();
        }

        String sport = event.path("strSport").isString() ? event.path("strSport").asString() : null;
        Duration duration = durations.apply(sport);

        Instant startsAt;
        Instant endsAt;
        LocalDate allDayDate = null;
        Optional<Instant> parsed = timestamp(event.path("strTimestamp").isString() ? event.path("strTimestamp").asString() : null);
        if (parsed.isPresent()) {
            startsAt = parsed.get();
            endsAt = startsAt.plus(duration);
        } else {
            Optional<LocalDateTime> dateTime = dateAndTime(event);
            if (dateTime.isPresent()) {
                startsAt = dateTime.get().toInstant(ZoneOffset.UTC);
                endsAt = startsAt.plus(duration);
            } else {
                Optional<LocalDate> date = allDayDate(event);
                if (date.isEmpty()) {
                    return Optional.empty();
                }
                allDayDate = date.get();
                startsAt = allDayDate.atStartOfDay(zone).toInstant();
                endsAt = allDayDate.plusDays(1).atStartOfDay(zone).toInstant();
            }
        }

        URI artwork = artwork(event, badge);
        return Optional.of(new SportsEvent("tsdb:" + idEvent, "thesportsdb:" + leagueId, title, startsAt, endsAt,
                allDayDate, artwork, mappedStatus));
    }

    private static String title(JsonNode event) {
        String raw = event.path("strEvent").asString("").strip();
        String title;
        if (!raw.isEmpty()) {
            title = raw;
        } else {
            String home = event.path("strHomeTeam").asString("").strip();
            String away = event.path("strAwayTeam").asString("").strip();
            if (home.isEmpty() || away.isEmpty()) {
                return null;
            }
            title = home + " vs " + away;
        }
        return title.length() > MAX_TITLE ? title.substring(0, MAX_TITLE - 1) + "…" : title;
    }

    private static Optional<LocalDateTime> dateAndTime(JsonNode event) {
        Optional<LocalDate> date = parseDate(event.path("dateEvent"));
        if (date.isEmpty()) {
            return Optional.empty();
        }
        String time = event.path("strTime").isString() ? event.path("strTime").asString() : "";
        if (time.length() >= 8) {
            String hhmmss = time.substring(0, 8);
            if (hhmmss.equals("00:00:00")) {
                return Optional.empty();
            }
            try {
                return Optional.of(LocalDateTime.of(date.get(), LocalTime.parse(hhmmss)));
            } catch (DateTimeParseException _) {
                return Optional.empty();
            }
        }
        if (time.length() >= 5) {
            String hhmm = time.substring(0, 5);
            if (hhmm.equals("00:00")) {
                return Optional.empty();
            }
            try {
                return Optional.of(LocalDateTime.of(date.get(), LocalTime.parse(hhmm)));
            } catch (DateTimeParseException _) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private static Optional<LocalDate> allDayDate(JsonNode event) {
        Optional<LocalDate> local = parseDate(event.path("dateEventLocal"));
        return local.isPresent() ? local : parseDate(event.path("dateEvent"));
    }

    private static Optional<LocalDate> parseDate(JsonNode node) {
        if (!node.isString()) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDate.parse(node.asString()));
        } catch (DateTimeParseException _) {
            return Optional.empty();
        }
    }

    private static URI artwork(JsonNode event, URI badge) {
        URI candidate = absoluteHttps(event.path("strThumb"));
        if (candidate == null) {
            candidate = absoluteHttps(event.path("strPoster"));
        }
        if (candidate == null) {
            return badge;
        }
        String host = candidate.getHost() == null ? "" : candidate.getHost().toLowerCase(Locale.ROOT);
        String path = candidate.getPath() == null ? "" : candidate.getPath().toLowerCase(Locale.ROOT);
        boolean thesportsdbHost = host.equals("thesportsdb.com") || host.endsWith(".thesportsdb.com");
        boolean imageExtension = path.endsWith(".jpg") || path.endsWith(".jpeg") || path.endsWith(".png");
        return thesportsdbHost && imageExtension ? URI.create(candidate.toString() + "/small") : candidate;
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

    private static String stringOf(JsonNode node) {
        String value = node.asString("");
        return value.isBlank() ? null : value;
    }

    private static Optional<Instant> timestamp(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String value = raw.strip();
        try {
            if (value.endsWith("Z") || value.endsWith("z") || OFFSET.matcher(value).find()) {
                return Optional.of(OffsetDateTime.parse(value).toInstant());
            }
            return Optional.of(LocalDateTime.parse(value).toInstant(ZoneOffset.UTC));
        } catch (DateTimeParseException _) {
            return Optional.empty();
        }
    }
}

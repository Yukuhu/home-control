package dev.andre.homecontrol.sources.sports.thesportsdb;

import dev.andre.homecontrol.sources.sports.SportsEvent;
import dev.andre.homecontrol.sources.sports.SportsProperties;
import dev.andre.homecontrol.sources.sports.SportsSettings;
import dev.andre.homecontrol.sources.sports.SportsSettingsService;
import dev.andre.homecontrol.sources.sports.SportsTimeZones;
import dev.andre.homecontrol.sources.sports.calendar.FeedStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Daily TheSportsDB fixtures for every chosen competition, cached per (league, UTC date) pair. */
public class TheSportsDbSchedule {

    private static final Logger log = LoggerFactory.getLogger(TheSportsDbSchedule.class);
    private static final Duration RETRY_BACKOFF = Duration.ofMinutes(10);

    public record Result(List<SportsEvent> events, List<String> errors, int feeds, int succeeded) {
        public Result {
            events = List.copyOf(events);
            errors = List.copyOf(errors);
        }
    }

    private record Entry(List<SportsEvent> events, Instant fetchedAt) {
    }

    private final TheSportsDbClient client;
    private final TheSportsDbKeys keys;
    private final SportsSettingsService settingsService;
    private final SportsProperties properties;
    private final SportsTimeZones zones;
    private final Clock clock;

    private final Map<String, Entry> cache = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastFailure = new ConcurrentHashMap<>();
    private final Map<String, String> errors = new ConcurrentHashMap<>();
    private volatile boolean ranOnce;

    public TheSportsDbSchedule(TheSportsDbClient client, TheSportsDbKeys keys, SportsSettingsService settingsService,
                               SportsProperties properties, SportsTimeZones zones, Clock clock) {
        this.client = client;
        this.keys = keys;
        this.settingsService = settingsService;
        this.properties = properties;
        this.zones = zones;
        this.clock = clock;
    }

    public boolean hasCompetitions() {
        return !settingsService.current().competitions().isEmpty();
    }

    public static Set<LocalDate> utcDates(Instant now, ZoneId zone) {
        LocalDate localDay = LocalDate.ofInstant(now, zone);
        Instant from = localDay.atStartOfDay(zone).toInstant().minus(Duration.ofHours(6));
        Instant to = localDay.plusDays(1).atStartOfDay(zone).toInstant();
        LocalDate first = LocalDate.ofInstant(from, ZoneOffset.UTC);
        LocalDate last = LocalDate.ofInstant(to.minusNanos(1), ZoneOffset.UTC);
        Set<LocalDate> dates = new LinkedHashSet<>();
        for (LocalDate d = first; !d.isAfter(last); d = d.plusDays(1)) {
            dates.add(d);
        }
        return dates;
    }

    public synchronized Result events() {
        ranOnce = true;
        SportsSettings settings = settingsService.current();
        Instant now = clock.instant();
        ZoneId zone = zones.effective();
        Set<LocalDate> dates = utcDates(now, zone);
        LocalDate firstDate = dates.stream().min(LocalDate::compareTo).orElse(null);

        Set<String> known = new LinkedHashSet<>();
        settings.competitions().forEach(c -> known.add(c.leagueId()));
        cache.keySet().removeIf(key -> !known.contains(leagueIdOf(key))
                || (firstDate != null && dateOf(key).isBefore(firstDate.minusDays(1))));
        lastFailure.keySet().removeIf(key -> !known.contains(leagueIdOf(key)));
        errors.keySet().removeIf(leagueId -> !known.contains(leagueId));

        List<String> errorList = new ArrayList<>();
        List<SportsEvent> allEvents = new ArrayList<>();
        int succeeded = 0;

        String key;
        try {
            key = keys.current();
        } catch (TheSportsDbException e) {
            for (SportsSettings.CompetitionEntry competition : settings.competitions()) {
                errors.put(competition.leagueId(), e.getMessage());
                errorList.add(competition.name() + ": " + e.getMessage());
            }
            return new Result(List.of(), errorList, settings.competitions().size(), 0);
        }

        boolean rateLimitedThisRound = false;
        for (SportsSettings.CompetitionEntry competition : settings.competitions()) {
            boolean hasEntry = false;
            for (LocalDate date : dates) {
                String cacheKey = cacheKey(competition.leagueId(), date);
                Entry entry = cache.get(cacheKey);
                boolean fresh = entry != null && entry.fetchedAt().plus(properties.theSportsDb().fixturesTtl()).isAfter(now);
                if (!fresh) {
                    Instant failedAt = lastFailure.get(cacheKey);
                    boolean recentFailure = failedAt != null && failedAt.plus(RETRY_BACKOFF).isAfter(now);
                    if (!recentFailure && !rateLimitedThisRound) {
                        try {
                            List<JsonNode> raw = client.eventsDay(key, date, competition.leagueId());
                            List<SportsEvent> mapped = new ArrayList<>();
                            for (JsonNode node : raw) {
                                TheSportsDbEventMapper.toEvent(node, competition.leagueId(), competition.badge(), zone,
                                        sport -> properties.theSportsDb().durationFor(sport, properties.defaultEventDuration()))
                                        .ifPresent(mapped::add);
                            }
                            entry = new Entry(mapped, now);
                            cache.put(cacheKey, entry);
                            lastFailure.remove(cacheKey);
                            errors.remove(competition.leagueId());
                        } catch (TheSportsDbException e) {
                            lastFailure.put(cacheKey, now);
                            errors.put(competition.leagueId(), e.getMessage());
                            log.warn("TheSportsDB fixtures for competition {} on {} failed ({})",
                                    competition.leagueId(), date, e.kind());
                            if (e.kind() == TheSportsDbException.Kind.RATE_LIMITED) {
                                rateLimitedThisRound = true;
                            }
                        }
                    } else if (rateLimitedThisRound && entry == null) {
                        errors.put(competition.leagueId(), "TheSportsDB is limiting requests; try again in a minute");
                    }
                }
                entry = cache.get(cacheKey);
                if (entry != null) {
                    hasEntry = true;
                    allEvents.addAll(entry.events());
                }
            }
            if (hasEntry) {
                succeeded++;
            }
            String competitionError = errors.get(competition.leagueId());
            if (competitionError != null) {
                errorList.add(competition.name() + ": " + competitionError);
            }
        }
        return new Result(allEvents, errorList, settings.competitions().size(), succeeded);
    }

    public Optional<SportsEvent> find(String itemId) {
        if (!ranOnce) {
            events();
        }
        for (Entry entry : cache.values()) {
            for (SportsEvent event : entry.events()) {
                if (event.itemId().equals(itemId)) {
                    return Optional.of(event);
                }
            }
        }
        return Optional.empty();
    }

    public Optional<FeedStatus> status(String leagueId) {
        if (!ranOnce) {
            events();
        }
        if (settingsService.current().competition(leagueId).isEmpty()) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        Set<LocalDate> dates = utcDates(now, zones.effective());
        Instant latest = null;
        int events = 0;
        for (LocalDate date : dates) {
            Entry entry = cache.get(cacheKey(leagueId, date));
            if (entry != null) {
                events += entry.events().size();
                if (latest == null || entry.fetchedAt().isAfter(latest)) {
                    latest = entry.fetchedAt();
                }
            }
        }
        return Optional.of(new FeedStatus(latest, events, errors.get(leagueId), 0, 0, 0));
    }

    public void forget(String leagueId) {
        cache.keySet().removeIf(key -> leagueIdOf(key).equals(leagueId));
        lastFailure.keySet().removeIf(key -> leagueIdOf(key).equals(leagueId));
        errors.remove(leagueId);
    }

    public void clear() {
        cache.clear();
        lastFailure.clear();
        errors.clear();
    }

    private static String cacheKey(String leagueId, LocalDate date) {
        return leagueId + "|" + date;
    }

    private static String leagueIdOf(String key) {
        return key.substring(0, key.indexOf('|'));
    }

    private static LocalDate dateOf(String key) {
        return LocalDate.parse(key.substring(key.indexOf('|') + 1));
    }
}

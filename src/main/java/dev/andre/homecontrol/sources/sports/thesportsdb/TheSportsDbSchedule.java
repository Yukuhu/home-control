package dev.andre.homecontrol.sources.sports.thesportsdb;

import dev.andre.homecontrol.core.content.ContentSourceException;
import dev.andre.homecontrol.sources.sports.feed.FeedFetches;
import dev.andre.homecontrol.sources.sports.feed.FeedResult;
import dev.andre.homecontrol.sources.sports.feed.FeedStatus;
import dev.andre.homecontrol.sources.sports.feed.SportsEvent;
import dev.andre.homecontrol.sources.sports.feed.SportsFeed;
import dev.andre.homecontrol.sources.sports.settings.SportsProperties;
import dev.andre.homecontrol.sources.sports.settings.SportsSettings;
import dev.andre.homecontrol.sources.sports.settings.SportsSettingsService;
import dev.andre.homecontrol.sources.sports.settings.SportsTimeZones;
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
import java.util.concurrent.atomic.AtomicLong;

/**
 * Daily TheSportsDB fixtures for every chosen competition, cached per (league, UTC date) pair.
 *
 * <p>No lock is held while a day downloads: {@link FeedFetches} runs one download per day at a time, and a pass that
 * finds one running waits for it. {@link #lock} guards only short steps: writing a download's outcome, publishing a
 * pass, {@link #forget} and {@link #clear}. A download writes its outcome only if its league is still configured and
 * no {@link #clear} advanced {@link #generation} since it started: a removal updates the settings before it forgets,
 * and a key change stores the key before it clears, so neither is undone by a download already running. Each download
 * reads the key after the generation, so what an old key fetched is never kept.
 */
public class TheSportsDbSchedule implements SportsFeed {

    private static final Logger log = LoggerFactory.getLogger(TheSportsDbSchedule.class);
    private static final String LIMITED = "TheSportsDB is limiting requests; try again in a minute";

    /** {@code zone}: the household zone the day's all-day events were placed in. */
    private record Entry(List<SportsEvent> events, Instant fetchedAt, ZoneId zone) {
    }

    /** A day's last failed download: when, whether TheSportsDB was limiting requests, and under which generation. */
    private record Failure(Instant at, boolean rateLimited, long generation) {
    }

    private final TheSportsDbClient client;
    private final TheSportsDbKeys keys;
    private final SportsSettingsService settingsService;
    private final SportsProperties properties;
    private final SportsTimeZones zones;
    private final Clock clock;

    private final Object lock = new Object();
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();
    private final Map<String, Failure> lastFailure = new ConcurrentHashMap<>();
    private final Map<String, String> errors = new ConcurrentHashMap<>();
    private final FeedFetches<String> fetches = new FeedFetches<>();
    private final AtomicLong generation = new AtomicLong();
    /** Set when a pass ends, so a lookup that arrives during the first pass runs one too and joins its downloads. */
    private volatile boolean ranOnce;
    /** Set when a pass begins: a status shows what is cached rather than wait for a pass that is already running. */
    private volatile boolean passStarted;

    public TheSportsDbSchedule(TheSportsDbClient client, TheSportsDbKeys keys, SportsSettingsService settingsService,
                               SportsProperties properties, SportsTimeZones zones, Clock clock) {
        this.client = client;
        this.keys = keys;
        this.settingsService = settingsService;
        this.properties = properties;
        this.zones = zones;
        this.clock = clock;
    }

    @Override
    public String itemPrefix() {
        return "tsdb:";
    }

    @Override
    public boolean configured() {
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

    @Override
    public FeedResult events() {
        passStarted = true;
        try {
            return pass();
        } finally {
            ranOnce = true;
        }
    }

    private FeedResult pass() {
        SportsSettings settings = settingsService.current();
        Instant now = clock.instant();
        ZoneId zone = zones.effective();
        Set<LocalDate> dates = utcDates(now, zone);
        dropUnknownAndOld(settings, dates);

        long started = generation.get();
        try {
            // Every download reads the key again; this only finds out before the first one that there is none.
            keys.current();
        } catch (TheSportsDbException e) {
            return keyUnavailable(settings, e, started);
        }
        refreshDue(settings.competitions(), dates, new Round(now, zone));
        // A key change clears what this pass fetched, and the refresh it asks for is skipped while this one runs: a
        // pass that a key change overtook starts over with the new key instead of publishing.
        return publish(dates, started).orElseGet(this::pass);
    }

    /**
     * One {@link #events()} pass: once TheSportsDB rate-limits a request, the rest of the pass stays on the cache,
     * unless the key has changed since.
     */
    private static final class Round {

        private final Instant now;
        private final ZoneId zone;
        private boolean rateLimited;
        /** The generation the rate limit was met under. */
        private long limitedUnder;

        Round(Instant now, ZoneId zone) {
            this.now = now;
            this.zone = zone;
        }
    }

    private void dropUnknownAndOld(SportsSettings settings, Set<LocalDate> dates) {
        LocalDate firstDate = dates.stream().min(LocalDate::compareTo).orElse(null);
        Set<String> known = new LinkedHashSet<>();
        settings.competitions().forEach(c -> known.add(c.leagueId()));
        cache.keySet().removeIf(key -> !known.contains(leagueIdOf(key))
                || (firstDate != null && dateOf(key).isBefore(firstDate.minusDays(1))));
        lastFailure.keySet().removeIf(key -> !known.contains(leagueIdOf(key)));
        errors.keySet().removeIf(leagueId -> !known.contains(leagueId));
    }

    private FeedResult keyUnavailable(SportsSettings settings, TheSportsDbException e, long started) {
        boolean stale;
        synchronized (lock) {
            stale = generation.get() != started;
            if (!stale) {
                settings.competitions().forEach(competition -> errors.put(competition.leagueId(), e.getMessage()));
            }
        }
        if (stale) {
            // A key was entered while this pass found none: start over with it.
            return pass();
        }
        List<String> errorList = settings.competitions().stream()
                .map(competition -> competition.name() + ": " + e.getMessage())
                .toList();
        return new FeedResult(List.of(), errorList, settings.competitions().size(), 0);
    }

    private void refreshDue(List<SportsSettings.CompetitionEntry> competitions, Set<LocalDate> dates, Round round) {
        for (SportsSettings.CompetitionEntry competition : competitions) {
            for (LocalDate date : dates) {
                // A pass whose wait was interrupted downloads nothing more; it still publishes what is cached.
                if (Thread.currentThread().isInterrupted()) {
                    return;
                }
                refreshIfDue(competition, date, round);
            }
        }
    }

    /** Downloads the day when it is stale and no recent failure or rate limit holds it back. */
    private void refreshIfDue(SportsSettings.CompetitionEntry competition, LocalDate date, Round round) {
        String cacheKey = cacheKey(competition.leagueId(), date);
        Entry entry = cache.get(cacheKey);
        if (fresh(entry, round)) {
            return;
        }
        boolean limited = limited(round);
        if (!failedRecently(cacheKey, round.now) && !limited) {
            fetches.run(cacheKey, () -> fetchIfStillDue(competition, date, cacheKey, round));
            noteRateLimit(cacheKey, round);
        } else if (limited && entry == null) {
            synchronized (lock) {
                if (generation.get() == round.limitedUnder) {
                    errors.put(competition.leagueId(), LIMITED);
                }
            }
        }
    }

    /** A rate limit met by this day's download, whichever pass ran it, holds back the rest of this pass too. */
    private void noteRateLimit(String cacheKey, Round round) {
        Failure failure = lastFailure.get(cacheKey);
        if (failure != null && failure.rateLimited() && failure.generation() == generation.get()
                && failure.at().plus(FeedFetches.RETRY_BACKOFF).isAfter(round.now)) {
            round.rateLimited = true;
            round.limitedUnder = failure.generation();
        }
    }

    /** Whether this pass met a rate limit under the key still in use. */
    private boolean limited(Round round) {
        return round.rateLimited && generation.get() == round.limitedUnder;
    }

    /** Fetched within the TTL, for the zone the household uses now: after a zone change its all-day days move. */
    private boolean fresh(Entry entry, Round round) {
        return entry != null && entry.zone().equals(round.zone)
                && entry.fetchedAt().plus(properties.theSportsDb().fixturesTtl()).isAfter(round.now);
    }

    private boolean failedRecently(String cacheKey, Instant now) {
        Failure failure = lastFailure.get(cacheKey);
        return failure != null && failure.at().plus(FeedFetches.RETRY_BACKOFF).isAfter(now);
    }

    /** Whether a download that started under {@code started} may write: its league kept, and no key change since. */
    private boolean wanted(SportsSettings.CompetitionEntry competition, long started) {
        return generation.get() == started && settingsService.current().competition(competition.leagueId()).isPresent();
    }

    /** One download: checks again, downloads without a lock, and writes the outcome if it is still wanted. */
    private void fetchIfStillDue(SportsSettings.CompetitionEntry competition, LocalDate date, String cacheKey,
                                 Round round) {
        long started = generation.get();
        if (settingsService.current().competition(competition.leagueId()).isEmpty()
                || fresh(cache.get(cacheKey), round) || failedRecently(cacheKey, round.now)) {
            return;
        }
        try {
            List<JsonNode> raw = client.eventsDay(keys.current(), date, competition.leagueId());
            List<SportsEvent> mapped = new ArrayList<>();
            for (JsonNode node : raw) {
                TheSportsDbEventMapper.toEvent(node, competition.leagueId(), competition.badge(), round.zone,
                        sport -> properties.theSportsDb().durationFor(sport, properties.defaultEventDuration()))
                        .ifPresent(mapped::add);
            }
            synchronized (lock) {
                if (wanted(competition, started)) {
                    cache.put(cacheKey, new Entry(mapped, round.now, round.zone));
                    lastFailure.remove(cacheKey);
                    errors.remove(competition.leagueId());
                }
            }
        } catch (TheSportsDbException e) {
            synchronized (lock) {
                if (wanted(competition, started)) {
                    lastFailure.put(cacheKey, new Failure(round.now,
                            e.kind() == ContentSourceException.Kind.RATE_LIMITED, started));
                    errors.put(competition.leagueId(), e.getMessage());
                }
            }
            log.warn("TheSportsDB fixtures for competition {} on {} failed ({})",
                    competition.leagueId(), date, e.kind());
        }
    }

    /**
     * The result for the competitions configured now, from the cache; empty when a key change came since
     * {@code started}.
     */
    private Optional<FeedResult> publish(Set<LocalDate> dates, long started) {
        synchronized (lock) {
            // Checked in the same step as the cache is read, so a key change either waits for this or is seen here.
            if (generation.get() != started) {
                return Optional.empty();
            }
            SportsSettings settings = settingsService.current();
            List<String> errorList = new ArrayList<>();
            List<SportsEvent> allEvents = new ArrayList<>();
            int succeeded = 0;
            for (SportsSettings.CompetitionEntry competition : settings.competitions()) {
                boolean hasEntry = false;
                for (LocalDate date : dates) {
                    Entry entry = cache.get(cacheKey(competition.leagueId(), date));
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
            return Optional.of(new FeedResult(allEvents, errorList, settings.competitions().size(), succeeded));
        }
    }

    @Override
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
        if (!passStarted) {
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
        synchronized (lock) {
            cache.keySet().removeIf(key -> leagueIdOf(key).equals(leagueId));
            lastFailure.keySet().removeIf(key -> leagueIdOf(key).equals(leagueId));
            errors.remove(leagueId);
        }
    }

    public void clear() {
        synchronized (lock) {
            generation.incrementAndGet();
            cache.clear();
            lastFailure.clear();
            errors.clear();
        }
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

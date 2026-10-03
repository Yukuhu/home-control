package dev.andre.homecontrol.sources.sports.calendar;

import dev.andre.homecontrol.core.content.ContentSourceException;
import dev.andre.homecontrol.sources.sports.feed.FeedFetches;
import dev.andre.homecontrol.sources.sports.feed.FeedResult;
import dev.andre.homecontrol.sources.sports.feed.FeedStatus;
import dev.andre.homecontrol.sources.sports.feed.SportsEvent;
import dev.andre.homecontrol.sources.sports.feed.SportsFeed;
import dev.andre.homecontrol.sources.sports.ics.IcsCalendar;
import dev.andre.homecontrol.sources.sports.ics.IcsEvent;
import dev.andre.homecontrol.sources.sports.ics.IcsFormatException;
import dev.andre.homecontrol.sources.sports.ics.IcsOccurrence;
import dev.andre.homecontrol.sources.sports.ics.IcsOccurrences;
import dev.andre.homecontrol.sources.sports.ics.IcsParser;
import dev.andre.homecontrol.sources.sports.ics.IcsZones;
import dev.andre.homecontrol.sources.sports.settings.SportsProperties;
import dev.andre.homecontrol.sources.sports.settings.SportsSettings;
import dev.andre.homecontrol.sources.sports.settings.SportsSettingsService;
import dev.andre.homecontrol.sources.sports.settings.SportsTimeZones;
import dev.andre.homecontrol.storage.SecretStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Every configured calendar, refetched on a schedule and expanded into a rolling window. Only fails a
 * calendar; a calendar keeps its last good parse across a transient failure.
 *
 * <p>No lock is held while a calendar downloads or a pass expands it: {@link FeedFetches} runs one download per
 * calendar at a time, and a pass that finds one running waits for it. {@link #lock} guards only short steps: writing a
 * download's outcome, reading the cache for a pass and publishing what it found, and {@link #forget}. A download
 * writes its outcome only if its calendar is still configured when it holds the lock; a removal updates the settings
 * before it forgets, so it is never undone by a download that was already running, and other calendars' downloads are
 * kept. A pass publishes only if no later pass has, and without the calendars forgotten while it expanded them.
 */
public class CalendarSchedule implements SportsFeed {

    private static final Logger log = LoggerFactory.getLogger(CalendarSchedule.class);
    private static final Duration WINDOW_BEFORE = Duration.ofDays(1);
    private static final Duration WINDOW_AFTER = Duration.ofDays(8);

    private record Cached(IcsCalendar calendar, Instant fetchedAt, Instant lastAttempt, String error) {
    }

    /** What the last pass found in one download of a calendar: the setup page shows it rather than expand again. */
    private record Expanded(Cached from, int events, int unsupportedRules, int unknownZones) {

        static Expanded of(Cached from, IcsOccurrences.Result result) {
            return new Expanded(from, result.occurrences().size(), result.unsupportedRules(), result.unknownZones());
        }
    }

    private final SportsSettingsService settingsService;
    private final CalendarFetcher fetcher;
    private final SecretStore secrets;
    private final SportsProperties properties;
    private final SportsTimeZones zones;
    private final Clock clock;

    private final Object lock = new Object();
    private final ConcurrentHashMap<String, Cached> cache = new ConcurrentHashMap<>();
    private final FeedFetches<String> fetches = new FeedFetches<>();
    /** Replaced under {@link #lock}, by a pass's publish step and by {@link #forget}; read without it. */
    private final AtomicReference<Map<String, SportsEvent>> byItemId = new AtomicReference<>(Map.of());
    private final ConcurrentHashMap<String, Expanded> expansions = new ConcurrentHashMap<>();
    /** Passes in the order they read the cache, and the latest that published; under {@link #lock}. */
    private long passes;
    private long published;
    /** Set when a pass ends, so a lookup that arrives during the first pass runs one too and joins its downloads. */
    private volatile boolean ranOnce;
    /** Set when a pass begins: a status shows what is cached rather than wait for a pass that is already running. */
    private volatile boolean passStarted;

    public CalendarSchedule(SportsSettingsService settingsService, CalendarFetcher fetcher, SecretStore secrets,
                            SportsProperties properties, SportsTimeZones zones, Clock clock) {
        this.settingsService = settingsService;
        this.fetcher = fetcher;
        this.secrets = secrets;
        this.properties = properties;
        this.zones = zones;
        this.clock = clock;
    }

    @Override
    public String itemPrefix() {
        return "ics:";
    }

    @Override
    public boolean configured() {
        return !settingsService.current().calendars().isEmpty();
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
        Set<String> known = settings.calendars().stream()
                .map(SportsSettings.CalendarEntry::id).collect(Collectors.toSet());
        cache.keySet().removeIf(id -> !known.contains(id));
        for (SportsSettings.CalendarEntry entry : settings.calendars()) {
            // A pass whose wait was interrupted downloads nothing more; it still publishes what is cached.
            if (Thread.currentThread().isInterrupted()) {
                break;
            }
            if (due(cache.get(entry.id()), now)) {
                fetches.run(entry.id(), () -> refreshIfStillDue(entry, now));
            }
        }
        return publish(now);
    }

    private boolean due(Cached cached, Instant now) {
        boolean stale = cached == null || cached.fetchedAt() == null
                || !cached.fetchedAt().plus(properties.calendar().refresh()).isAfter(now);
        boolean cooledDown = cached == null || cached.error() == null || cached.lastAttempt() == null
                || !cached.lastAttempt().plus(FeedFetches.RETRY_BACKOFF).isAfter(now);
        return stale && cooledDown;
    }

    /** One download: checks again, downloads without a lock, and writes the outcome if the calendar is still wanted. */
    private void refreshIfStillDue(SportsSettings.CalendarEntry entry, Instant now) {
        if (settingsService.current().calendar(entry.id()).isEmpty()) {
            return;
        }
        Cached previous = cache.get(entry.id());
        if (!due(previous, now)) {
            return;
        }
        Cached next = refresh(entry, previous, now);
        synchronized (lock) {
            if (settingsService.current().calendar(entry.id()).isPresent()) {
                cache.put(entry.id(), next);
            }
        }
    }

    /** The result for the calendars configured now, from the cache; also replaces the item index. */
    private FeedResult publish(Instant now) {
        SportsSettings settings;
        Map<String, Cached> cached = new HashMap<>();
        long pass;
        synchronized (lock) {
            settings = settingsService.current();
            settings.calendars().forEach(entry -> Optional.ofNullable(cache.get(entry.id()))
                    .ifPresent(found -> cached.put(entry.id(), found)));
            pass = ++passes;
        }
        ZoneId household = zones.effective();
        List<SportsEvent> events = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int succeeded = 0;
        Map<String, SportsEvent> byId = new HashMap<>();
        for (SportsSettings.CalendarEntry entry : settings.calendars()) {
            Cached calendar = cached.get(entry.id());
            if (calendar != null && calendar.calendar() != null) {
                succeeded++;
                for (SportsEvent event : toEvents(entry.id(), calendar.calendar(),
                        expand(entry.id(), calendar, now, household).occurrences(), household)) {
                    events.add(event);
                    byId.put(event.itemId(), event);
                    if (event.formerItemId() != null) {
                        byId.putIfAbsent(event.formerItemId(), event); // pins and links made under the former id
                    }
                }
            }
            if (calendar != null && calendar.error() != null) {
                errors.add(entry.label() + ": " + calendar.error());
            }
        }
        synchronized (lock) {
            // A later pass may have published already; a calendar removed meanwhile keeps none of its events.
            if (pass > published) {
                published = pass;
                Set<String> kept = cache.keySet().stream().map(SportsSettings::calendarKey).collect(Collectors.toSet());
                byId.values().removeIf(event -> !kept.contains(event.competitionKey()));
                byItemId.set(Map.copyOf(byId));
            }
        }
        return new FeedResult(events, errors, settings.calendars().size(), succeeded);
    }

    /** Expands one download into the window around {@code now}, and keeps what it found for the setup page. */
    private IcsOccurrences.Result expand(String calendarId, Cached calendar, Instant now, ZoneId household) {
        IcsOccurrences.Result expanded = IcsOccurrences.expand(calendar.calendar(), household,
                now.minus(WINDOW_BEFORE), now.plus(WINDOW_AFTER), properties.defaultEventDuration());
        expansions.put(calendarId, Expanded.of(calendar, expanded));
        return expanded;
    }

    private Cached refresh(SportsSettings.CalendarEntry entry, Cached previous, Instant now) {
        IcsCalendar keep = previous == null ? null : previous.calendar();
        Instant keptFetchedAt = previous == null ? null : previous.fetchedAt();
        Optional<String> secret = secrets.secret(SportsCalendars.secretName(entry.id()));
        if (secret.isEmpty()) {
            log.warn("Calendar {} could not be refreshed ({})", entry.id(), "MISSING_SECRET");
            return new Cached(keep, keptFetchedAt, now,
                    "The link for " + entry.label() + " is missing; remove the calendar and add it again");
        }
        try {
            IcsCalendar calendar = parse(fetcher.fetch(URI.create(secret.get())));
            return new Cached(calendar, now, now, null);
        } catch (ContentSourceException e) {
            String kind = e instanceof CalendarFetchException cfe ? cfe.kind().name() : "ERROR";
            log.warn("Calendar {} could not be refreshed ({})", entry.id(), kind);
            return new Cached(keep, keptFetchedAt, now, e.getMessage());
        }
    }

    private static IcsCalendar parse(String text) {
        try {
            return IcsParser.parse(text);
        } catch (IcsFormatException e) {
            throw new CalendarFetchException(ContentSourceException.Kind.BAD_RESPONSE, e.getMessage());
        }
    }

    @Override
    public Optional<SportsEvent> find(String itemId) {
        if (!ranOnce) {
            events();
        }
        return Optional.ofNullable(byItemId.get().get(itemId));
    }

    public Optional<FeedStatus> status(String calendarId) {
        if (!passStarted) {
            events();
        }
        SportsSettings settings = settingsService.current();
        if (settings.calendar(calendarId).isEmpty()) {
            return Optional.empty();
        }
        Cached cached = cache.get(calendarId);
        if (cached == null) {
            return Optional.of(new FeedStatus(null, 0, null, 0, 0, 0));
        }
        if (cached.calendar() == null) {
            return Optional.of(new FeedStatus(cached.fetchedAt(), 0, cached.error(), 0, 0, 0));
        }
        // What the last pass found in this download; a calendar no pass has expanded yet is expanded here.
        Expanded last = expansions.get(calendarId);
        if (last == null || !cached.equals(last.from())) {
            last = Expanded.of(cached, expand(calendarId, cached, clock.instant(), zones.effective()));
        }
        return Optional.of(new FeedStatus(cached.fetchedAt(), last.events(), cached.error(), last.unsupportedRules(),
                last.unknownZones(), cached.calendar().skippedEvents()));
    }

    public void prime(String calendarId, IcsCalendar calendar) {
        Instant now = clock.instant();
        cache.put(calendarId, new Cached(calendar, now, now, null));
    }

    public void forget(String calendarId) {
        synchronized (lock) {
            cache.remove(calendarId);
            expansions.remove(calendarId);
            String key = SportsSettings.calendarKey(calendarId);
            byItemId.updateAndGet(current -> {
                Map<String, SportsEvent> next = new HashMap<>(current);
                next.values().removeIf(event -> event.competitionKey().equals(key));
                return Map.copyOf(next);
            });
        }
    }

    /**
     * One calendar's events. A UID that the calendar gives more than one event, against RFC 5545, names none of them:
     * each is named by its start, as before ids came from UIDs, so its id does not depend on what else is in the
     * window. Older versions placed an all-day event at midnight in the calendar's zone, and made its id from that
     * start.
     */
    static List<SportsEvent> toEvents(String calendarId, IcsCalendar calendar, List<IcsOccurrence> occurrences,
                                      ZoneId household) {
        Set<String> reused = reusedUids(calendar);
        ZoneId calendarZone = IcsZones.resolve(calendar.timeZone()).orElse(household);
        List<SportsEvent> events = new ArrayList<>(occurrences.size());
        for (IcsOccurrence occurrence : occurrences) {
            Instant formerStart = occurrence.allDayDate() == null ? occurrence.startsAt()
                    : occurrence.allDayDate().atStartOfDay(calendarZone).toInstant();
            events.add(toEvent(calendarId, occurrence, reused.contains(occurrence.uid()), formerStart));
        }
        return events;
    }

    /** UIDs that more than one event uses for itself, rather than to move one occurrence of a series. */
    private static Set<String> reusedUids(IcsCalendar calendar) {
        Set<String> seen = new HashSet<>();
        Set<String> reused = new HashSet<>();
        for (IcsEvent event : calendar.events()) {
            if (event.uid() != null && event.recurrenceId() == null && !seen.add(event.uid())) {
                reused.add(event.uid());
            }
        }
        return reused;
    }

    /** An event of a calendar whose UIDs are its own, with its former id made from its start. */
    public static SportsEvent toEvent(String calendarId, IcsOccurrence occurrence) {
        return toEvent(calendarId, occurrence, false, occurrence.startsAt());
    }

    /**
     * The event's id comes from its UID and, for a repeating one, the place the occurrence has in its series: a
     * kick-off that moves keeps its id, and with it its pinned link. Without a UID of its own, its start names it.
     */
    private static SportsEvent toEvent(String calendarId, IcsOccurrence occurrence, boolean reusedUid,
                                       Instant formerStart) {
        String stripped = occurrence.summary() == null ? "" : occurrence.summary().strip();
        String title = stripped.isBlank() ? "Event" : stripped;
        if (title.length() > 200) {
            title = title.substring(0, 199) + "…";
        }
        String formerItemId = "ics:" + calendarId + ":" + hashHex16(formerIdentity(occurrence, formerStart));
        String itemId = occurrence.uid() == null || reusedUid ? formerItemId
                : "ics:" + calendarId + ":" + hashHex16(identity(occurrence));
        return new SportsEvent(itemId, SportsSettings.calendarKey(calendarId), title, occurrence.startsAt(),
                occurrence.endsAt(), occurrence.allDayDate(), null, SportsEvent.Status.SCHEDULED,
                itemId.equals(formerItemId) ? null : formerItemId);
    }

    private static String identity(IcsOccurrence occurrence) {
        return "uid|" + occurrence.uid()
                + (occurrence.recurrenceId() == null ? "" : "|" + occurrence.recurrenceId().getEpochSecond());
    }

    /** What ids were made of before UIDs: the UID and the start, so a moved kick-off got a new id. */
    private static String formerIdentity(IcsOccurrence occurrence, Instant start) {
        return occurrence.uid() != null
                ? occurrence.uid() + "|" + start.getEpochSecond()
                : "no-uid|" + occurrence.summary() + "|" + start.getEpochSecond();
    }

    private static String hashHex16(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}

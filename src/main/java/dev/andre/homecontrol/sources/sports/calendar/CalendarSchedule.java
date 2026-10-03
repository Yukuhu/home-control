package dev.andre.homecontrol.sources.sports.calendar;

import dev.andre.homecontrol.core.content.ContentSourceException;
import dev.andre.homecontrol.sources.sports.feed.FeedFetches;
import dev.andre.homecontrol.sources.sports.feed.FeedResult;
import dev.andre.homecontrol.sources.sports.feed.FeedStatus;
import dev.andre.homecontrol.sources.sports.feed.SportsEvent;
import dev.andre.homecontrol.sources.sports.feed.SportsFeed;
import dev.andre.homecontrol.sources.sports.ics.IcsCalendar;
import dev.andre.homecontrol.sources.sports.ics.IcsFormatException;
import dev.andre.homecontrol.sources.sports.ics.IcsOccurrence;
import dev.andre.homecontrol.sources.sports.ics.IcsOccurrences;
import dev.andre.homecontrol.sources.sports.ics.IcsParser;
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
 * <p>No lock is held while a calendar downloads: {@link FeedFetches} runs one download per calendar at a time, and a
 * pass that finds one running waits for it. {@link #lock} guards only short steps: writing a download's outcome,
 * publishing a pass, and {@link #forget}. A download writes its outcome only if its calendar is still configured when
 * it holds the lock; a removal updates the settings before it forgets, so it is never undone by a download that was
 * already running, and other calendars' downloads are kept.
 */
public class CalendarSchedule implements SportsFeed {

    private static final Logger log = LoggerFactory.getLogger(CalendarSchedule.class);
    private static final Duration WINDOW_BEFORE = Duration.ofDays(1);
    private static final Duration WINDOW_AFTER = Duration.ofDays(8);

    private record Cached(IcsCalendar calendar, Instant fetchedAt, Instant lastAttempt, String error) {
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
        synchronized (lock) {
            SportsSettings settings = settingsService.current();
            Instant windowStart = now.minus(WINDOW_BEFORE);
            Instant windowEnd = now.plus(WINDOW_AFTER);
            List<SportsEvent> events = new ArrayList<>();
            List<String> errors = new ArrayList<>();
            int succeeded = 0;
            Map<String, SportsEvent> byId = new HashMap<>();
            for (SportsSettings.CalendarEntry entry : settings.calendars()) {
                Cached cached = cache.get(entry.id());
                if (cached != null && cached.calendar() != null) {
                    succeeded++;
                    IcsOccurrences.Result expanded = IcsOccurrences.expand(cached.calendar(), zones.effective(),
                            windowStart, windowEnd, properties.defaultEventDuration());
                    for (SportsEvent event : toEvents(entry.id(), expanded.occurrences())) {
                        events.add(event);
                        byId.put(event.itemId(), event);
                        if (event.formerItemId() != null) {
                            byId.putIfAbsent(event.formerItemId(), event); // pins and links made under the former id
                        }
                    }
                }
                if (cached != null && cached.error() != null) {
                    errors.add(entry.label() + ": " + cached.error());
                }
            }
            byItemId.set(Map.copyOf(byId));
            return new FeedResult(events, errors, settings.calendars().size(), succeeded);
        }
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
        int events = 0;
        int unsupported = 0;
        int unknownZones = 0;
        int skipped = 0;
        if (cached.calendar() != null) {
            Instant now = clock.instant();
            IcsOccurrences.Result expanded = IcsOccurrences.expand(cached.calendar(), zones.effective(),
                    now.minus(WINDOW_BEFORE), now.plus(WINDOW_AFTER), properties.defaultEventDuration());
            events = expanded.occurrences().size();
            unsupported = expanded.unsupportedRules();
            unknownZones = expanded.unknownZones();
            skipped = cached.calendar().skippedEvents();
        }
        return Optional.of(new FeedStatus(cached.fetchedAt(), events, cached.error(), unsupported, unknownZones, skipped));
    }

    public void prime(String calendarId, IcsCalendar calendar) {
        Instant now = clock.instant();
        cache.put(calendarId, new Cached(calendar, now, now, null));
    }

    public void forget(String calendarId) {
        synchronized (lock) {
            cache.remove(calendarId);
            String key = SportsSettings.calendarKey(calendarId);
            byItemId.updateAndGet(current -> {
                Map<String, SportsEvent> next = new HashMap<>(current);
                next.values().removeIf(event -> event.competitionKey().equals(key));
                return Map.copyOf(next);
            });
        }
    }


    /**
     * One calendar's events. A feed that reuses a UID for different events, against RFC 5545, would give them one id:
     * each after the first keeps its former id, which includes its start, instead.
     */
    static List<SportsEvent> toEvents(String calendarId, List<IcsOccurrence> occurrences) {
        Set<String> taken = new HashSet<>();
        List<SportsEvent> events = new ArrayList<>(occurrences.size());
        for (IcsOccurrence occurrence : occurrences) {
            SportsEvent event = toEvent(calendarId, occurrence);
            if (!taken.add(event.itemId()) && event.formerItemId() != null) {
                event = new SportsEvent(event.formerItemId(), event.competitionKey(), event.title(), event.startsAt(),
                        event.endsAt(), event.allDayDate(), event.artwork(), event.status(), null);
                taken.add(event.itemId());
            }
            events.add(event);
        }
        return events;
    }

    /**
     * The event's id comes from its UID and, for a repeating one, the start the occurrence has in its series: a kick-off
     * that moves keeps its id, and with it its pinned link. Without a UID nothing else names the event.
     */
    public static SportsEvent toEvent(String calendarId, IcsOccurrence occurrence) {
        String stripped = occurrence.summary() == null ? "" : occurrence.summary().strip();
        String title = stripped.isBlank() ? "Event" : stripped;
        if (title.length() > 200) {
            title = title.substring(0, 199) + "…";
        }
        String formerItemId = "ics:" + calendarId + ":" + hashHex16(formerIdentity(occurrence));
        String itemId = occurrence.uid() == null ? formerItemId
                : "ics:" + calendarId + ":" + hashHex16("uid|" + occurrence.uid()
                        + (occurrence.recurrenceId() == null ? "" : "|" + occurrence.recurrenceId().getEpochSecond()));
        return new SportsEvent(itemId, SportsSettings.calendarKey(calendarId), title, occurrence.startsAt(),
                occurrence.endsAt(), occurrence.allDayDate(), null, SportsEvent.Status.SCHEDULED,
                itemId.equals(formerItemId) ? null : formerItemId);
    }

    /** What the id was made of before it came from the UID: the UID and the start, so a moved kick-off got a new one. */
    private static String formerIdentity(IcsOccurrence occurrence) {
        return occurrence.uid() != null
                ? occurrence.uid() + "|" + occurrence.startsAt().getEpochSecond()
                : "no-uid|" + occurrence.summary() + "|" + occurrence.startsAt().getEpochSecond();
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

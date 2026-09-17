package dev.andre.homecontrol.sources.sports.calendar;

import dev.andre.homecontrol.core.content.ContentSourceException;
import dev.andre.homecontrol.sources.sports.SportsEvent;
import dev.andre.homecontrol.sources.sports.SportsProperties;
import dev.andre.homecontrol.sources.sports.SportsSettings;
import dev.andre.homecontrol.sources.sports.SportsSettingsService;
import dev.andre.homecontrol.sources.sports.SportsTimeZones;
import dev.andre.homecontrol.sources.sports.ics.IcsCalendar;
import dev.andre.homecontrol.sources.sports.ics.IcsFormatException;
import dev.andre.homecontrol.sources.sports.ics.IcsOccurrence;
import dev.andre.homecontrol.sources.sports.ics.IcsOccurrences;
import dev.andre.homecontrol.sources.sports.ics.IcsParser;
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
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Every configured calendar, refetched on a schedule and expanded into a rolling window. Only fails a
 * calendar; a calendar keeps its last good parse across a transient failure.
 */
public class CalendarSchedule {

    private static final Logger log = LoggerFactory.getLogger(CalendarSchedule.class);
    private static final Duration RETRY_BACKOFF = Duration.ofMinutes(10);
    private static final Duration WINDOW_BEFORE = Duration.ofDays(1);
    private static final Duration WINDOW_AFTER = Duration.ofDays(8);

    public record Result(List<SportsEvent> events, List<String> errors, int feeds, int succeeded) {
        public Result {
            events = List.copyOf(events);
            errors = List.copyOf(errors);
        }
    }

    private record Cached(IcsCalendar calendar, Instant fetchedAt, Instant lastAttempt, String error) {
    }

    private final SportsSettingsService settingsService;
    private final CalendarFetcher fetcher;
    private final SecretStore secrets;
    private final SportsProperties properties;
    private final SportsTimeZones zones;
    private final Clock clock;

    private final ConcurrentHashMap<String, Cached> cache = new ConcurrentHashMap<>();
    private volatile Map<String, SportsEvent> byItemId = Map.of();
    private volatile boolean ranOnce;

    public CalendarSchedule(SportsSettingsService settingsService, CalendarFetcher fetcher, SecretStore secrets,
                            SportsProperties properties, SportsTimeZones zones, Clock clock) {
        this.settingsService = settingsService;
        this.fetcher = fetcher;
        this.secrets = secrets;
        this.properties = properties;
        this.zones = zones;
        this.clock = clock;
    }

    public boolean hasCalendars() {
        return !settingsService.current().calendars().isEmpty();
    }

    public synchronized Result events() {
        ranOnce = true;
        SportsSettings settings = settingsService.current();
        Instant now = clock.instant();
        Instant windowStart = now.minus(WINDOW_BEFORE);
        Instant windowEnd = now.plus(WINDOW_AFTER);

        Set<String> known = settings.calendars().stream()
                .map(SportsSettings.CalendarEntry::id).collect(Collectors.toSet());
        cache.keySet().removeIf(id -> !known.contains(id));

        List<SportsEvent> events = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int succeeded = 0;
        Map<String, SportsEvent> byId = new HashMap<>();

        for (SportsSettings.CalendarEntry entry : settings.calendars()) {
            Cached cached = cache.get(entry.id());
            boolean stale = cached == null || cached.fetchedAt() == null
                    || !cached.fetchedAt().plus(properties.calendar().refresh()).isAfter(now);
            boolean cooledDown = cached == null || cached.error() == null || cached.lastAttempt() == null
                    || !cached.lastAttempt().plus(RETRY_BACKOFF).isAfter(now);
            if (stale && cooledDown) {
                cached = refresh(entry, cached, now);
                cache.put(entry.id(), cached);
            }
            if (cached != null && cached.calendar() != null) {
                succeeded++;
                IcsOccurrences.Result expanded = IcsOccurrences.expand(cached.calendar(), zones.effective(),
                        windowStart, windowEnd, properties.defaultEventDuration());
                for (IcsOccurrence occurrence : expanded.occurrences()) {
                    SportsEvent event = toEvent(entry.id(), occurrence);
                    events.add(event);
                    byId.put(event.itemId(), event);
                }
            }
            if (cached != null && cached.error() != null) {
                errors.add(entry.label() + ": " + cached.error());
            }
        }
        byItemId = Map.copyOf(byId);
        return new Result(events, errors, settings.calendars().size(), succeeded);
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
            String text = fetcher.fetch(URI.create(secret.get()));
            IcsCalendar calendar;
            try {
                calendar = IcsParser.parse(text);
            } catch (IcsFormatException e) {
                throw new CalendarFetchException(CalendarFetchException.Kind.NOT_A_CALENDAR, e.getMessage());
            }
            return new Cached(calendar, now, now, null);
        } catch (ContentSourceException e) {
            String kind = e instanceof CalendarFetchException cfe ? cfe.kind().name() : "ERROR";
            log.warn("Calendar {} could not be refreshed ({})", entry.id(), kind);
            return new Cached(keep, keptFetchedAt, now, e.getMessage());
        }
    }

    public Optional<SportsEvent> find(String itemId) {
        if (!ranOnce) {
            events();
        }
        return Optional.ofNullable(byItemId.get(itemId));
    }

    public Optional<FeedStatus> status(String calendarId) {
        if (!ranOnce) {
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
        cache.remove(calendarId);
        Map<String, SportsEvent> next = new HashMap<>(byItemId);
        String key = SportsSettings.calendarKey(calendarId);
        next.values().removeIf(event -> event.competitionKey().equals(key));
        byItemId = Map.copyOf(next);
    }

    public static SportsEvent toEvent(String calendarId, IcsOccurrence occurrence) {
        String stripped = occurrence.summary() == null ? "" : occurrence.summary().strip();
        String title = stripped.isBlank() ? "Event" : stripped;
        if (title.length() > 200) {
            title = title.substring(0, 199) + "…";
        }
        String hashInput = occurrence.uid() != null
                ? occurrence.uid() + "|" + occurrence.startsAt().getEpochSecond()
                : "no-uid|" + occurrence.summary() + "|" + occurrence.startsAt().getEpochSecond();
        String itemId = "ics:" + calendarId + ":" + hashHex16(hashInput);
        return new SportsEvent(itemId, SportsSettings.calendarKey(calendarId), title, occurrence.startsAt(),
                occurrence.endsAt(), occurrence.allDayDate(), null, SportsEvent.Status.SCHEDULED);
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

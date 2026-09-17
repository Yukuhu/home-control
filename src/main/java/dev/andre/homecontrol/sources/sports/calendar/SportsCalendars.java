package dev.andre.homecontrol.sources.sports.calendar;

import dev.andre.homecontrol.security.LoginService;
import dev.andre.homecontrol.sources.sports.SportsProperties;
import dev.andre.homecontrol.sources.sports.SportsSettings;
import dev.andre.homecontrol.sources.sports.SportsSettingsService;
import dev.andre.homecontrol.sources.sports.ics.IcsCalendar;
import dev.andre.homecontrol.sources.sports.ics.IcsFormatException;
import dev.andre.homecontrol.sources.sports.ics.IcsParser;
import dev.andre.homecontrol.storage.SecretStore;
import jakarta.servlet.http.HttpServletRequest;

import java.net.URI;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Adds and removes calendars: the only writer of their secrets and settings entries. */
public class SportsCalendars {

    public record AddCalendar(String url, String label, String loginPassword, String loginPasswordConfirmation) {
        @Override
        public String toString() {
            return "AddCalendar[label=" + label + "]";
        }
    }

    private final SportsSettingsService settingsService;
    private final CalendarUrlPolicy policy;
    private final CalendarFetcher fetcher;
    private final CalendarSchedule schedule;
    private final SecretStore secrets;
    private final LoginService login;
    private final SportsProperties properties;
    private final Clock clock;
    private final SecureRandom random;

    public SportsCalendars(SportsSettingsService settingsService, CalendarUrlPolicy policy, CalendarFetcher fetcher,
                           CalendarSchedule schedule, SecretStore secrets, LoginService login,
                           SportsProperties properties, Clock clock, SecureRandom random) {
        this.settingsService = settingsService;
        this.policy = policy;
        this.fetcher = fetcher;
        this.schedule = schedule;
        this.secrets = secrets;
        this.login = login;
        this.properties = properties;
        this.clock = clock;
        this.random = random;
    }

    public static String secretName(String calendarId) {
        return "sports.calendar." + calendarId;
    }

    public synchronized SportsSettings.CalendarEntry add(AddCalendar request, HttpServletRequest http) {
        URI uri = policy.parse(request.url());
        String label = request.label() == null ? "" : request.label().strip();
        if (label.length() > 80) {
            throw new IllegalArgumentException("Keep the name under 80 characters");
        }
        SportsSettings settings = settingsService.current();
        if (settings.calendars().size() >= properties.maxCalendars()) {
            throw new IllegalArgumentException("You can add up to " + properties.maxCalendars() + " calendars");
        }
        String uriString = uri.toString();
        boolean duplicate = settings.calendars().stream()
                .anyMatch(c -> secrets.secret(secretName(c.id())).map(uriString::equals).orElse(false));
        if (duplicate) {
            throw new IllegalArgumentException("That calendar is already added");
        }
        if (!login.loginRequired()) {
            login.checkNewPassword(request.loginPassword(), request.loginPasswordConfirmation());
        }
        String text = fetcher.fetch(uri);
        IcsCalendar parsed;
        try {
            parsed = IcsParser.parse(text);
        } catch (IcsFormatException e) {
            throw new IllegalArgumentException(e.getMessage());
        }
        String id = newId(settings);
        login.storeSecrets(Map.of(secretName(id), uriString), request.loginPassword(),
                request.loginPasswordConfirmation(), http);
        String resolvedLabel = !label.isEmpty() ? label
                : parsed.name() != null && !parsed.name().isBlank() ? cut(parsed.name()) : uri.getHost();
        SportsSettings.CalendarEntry entry =
                new SportsSettings.CalendarEntry(id, resolvedLabel, uri.getHost(), null, clock.instant());
        try {
            settingsService.update(s -> s.withCalendars(append(s.calendars(), entry)));
        } catch (RuntimeException e) {
            login.removeSecrets(List.of(secretName(id)));
            throw e;
        }
        schedule.prime(id, parsed);
        return entry;
    }

    public synchronized SportsSettings.CalendarEntry remove(String id) {
        SportsSettings.CalendarEntry entry = settingsService.current().calendar(id)
                .orElseThrow(() -> new IllegalArgumentException("No calendar " + id));
        settingsService.update(s -> s.withCalendars(without(s.calendars(), id)));
        login.removeSecrets(List.of(secretName(id)));
        schedule.forget(id);
        return entry;
    }

    private String newId(SportsSettings settings) {
        for (int attempt = 0; attempt < 50; attempt++) {
            byte[] bytes = new byte[6];
            random.nextBytes(bytes);
            String id = "c-" + HexFormat.of().formatHex(bytes);
            if (settings.calendar(id).isEmpty()) {
                return id;
            }
        }
        throw new IllegalStateException("Could not generate a calendar id");
    }

    private static String cut(String value) {
        return value.length() > 80 ? value.substring(0, 80) : value;
    }

    private static List<SportsSettings.CalendarEntry> append(List<SportsSettings.CalendarEntry> calendars,
                                                              SportsSettings.CalendarEntry entry) {
        List<SportsSettings.CalendarEntry> next = new ArrayList<>(calendars);
        next.add(entry);
        return next;
    }

    private static List<SportsSettings.CalendarEntry> without(List<SportsSettings.CalendarEntry> calendars, String id) {
        List<SportsSettings.CalendarEntry> next = new ArrayList<>(calendars);
        next.removeIf(c -> c.id().equals(id));
        return next;
    }
}

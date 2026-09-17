package dev.andre.homecontrol.sources.sports;

import dev.andre.homecontrol.security.LoginService;
import dev.andre.homecontrol.sources.sports.calendar.CalendarSchedule;
import dev.andre.homecontrol.sources.sports.calendar.FeedStatus;
import dev.andre.homecontrol.web.SetupController;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** What the setup page shows about the sports source: time zone and calendar status. */
@ControllerAdvice(assignableTypes = SetupController.class)
@ConditionalOnProperty(name = "home-control.sports.enabled", havingValue = "true", matchIfMissing = true)
public class SportsSetupAdvice {

    public record CalendarView(String id, String label, String host, String status) {
    }

    /** What the setup page shows about the sports source. */
    public record View(String timeZone, String storedTimeZone, boolean timeZoneLooksUnset,
                       List<CalendarView> calendars, int maxCalendars, boolean needsLoginPassword) {
    }

    private final ObjectProvider<SportsSettingsService> settingsProvider;
    private final ObjectProvider<SportsTimeZones> zonesProvider;
    private final ObjectProvider<CalendarSchedule> scheduleProvider;
    private final ObjectProvider<LoginService> loginProvider;
    private final ObjectProvider<SportsProperties> propertiesProvider;

    public SportsSetupAdvice(ObjectProvider<SportsSettingsService> settingsProvider,
                             ObjectProvider<SportsTimeZones> zonesProvider,
                             ObjectProvider<CalendarSchedule> scheduleProvider,
                             ObjectProvider<LoginService> loginProvider,
                             ObjectProvider<SportsProperties> propertiesProvider) {
        this.settingsProvider = settingsProvider;
        this.zonesProvider = zonesProvider;
        this.scheduleProvider = scheduleProvider;
        this.loginProvider = loginProvider;
        this.propertiesProvider = propertiesProvider;
    }

    @ModelAttribute("sports")
    public View sports() {
        SportsSettingsService settings = settingsProvider.getIfAvailable();
        SportsTimeZones zones = zonesProvider.getIfAvailable();
        CalendarSchedule schedule = scheduleProvider.getIfAvailable();
        LoginService login = loginProvider.getIfAvailable();
        SportsProperties properties = propertiesProvider.getIfAvailable();
        if (settings == null || zones == null || schedule == null || login == null || properties == null) {
            return null;
        }
        SportsSettings current = settings.current();
        ZoneId zone = zones.effective();
        boolean looksUnset = !zones.chosen() && zone.normalized().equals(ZoneOffset.UTC);

        List<CalendarView> calendarViews = new ArrayList<>();
        for (SportsSettings.CalendarEntry entry : current.calendars()) {
            FeedStatus status = schedule.status(entry.id()).orElse(null);
            calendarViews.add(new CalendarView(entry.id(), entry.label(), entry.host(),
                    statusText(status, entry.label(), zone)));
        }
        return new View(zone.getId(), current.timeZone() == null ? "" : current.timeZone(), looksUnset,
                List.copyOf(calendarViews), properties.maxCalendars(), !login.loginRequired());
    }

    static String statusText(FeedStatus status, String label, ZoneId zone) {
        if (status == null || (status.fetchedAt() == null && status.error() == null)) {
            return "Not loaded yet";
        }
        StringBuilder text = new StringBuilder();
        if (status.error() != null) {
            String prefix = label + ": ";
            String message = status.error().startsWith(prefix) ? status.error().substring(prefix.length()) : status.error();
            text.append("Could not refresh: ").append(message);
        } else {
            String time = DateTimeFormatter.ofPattern("HH:mm").withZone(zone).format(status.fetchedAt());
            text.append(status.events()).append(" events · updated ").append(time);
        }
        if (status.unsupportedRules() > 0) {
            text.append("; ").append(status.unsupportedRules())
                    .append(status.unsupportedRules() == 1 ? " repeating event uses" : " repeating events use")
                    .append(" rules Home Control shows only once");
        }
        if (status.unknownZones() > 0) {
            text.append("; ").append(status.unknownZones())
                    .append(status.unknownZones() == 1 ? " unknown time zone" : " unknown time zones");
        }
        if (status.skippedEvents() > 0) {
            text.append("; ").append(status.skippedEvents())
                    .append(status.skippedEvents() == 1 ? " unreadable event" : " unreadable events");
        }
        return text.toString();
    }
}

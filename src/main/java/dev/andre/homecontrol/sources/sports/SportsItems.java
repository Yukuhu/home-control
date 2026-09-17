package dev.andre.homecontrol.sources.sports;

import dev.andre.homecontrol.core.playback.ContentItem;
import dev.andre.homecontrol.core.playback.ContentKind;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.List;
import java.util.Locale;

/** Turns a sports event into a content item and the one-line subtitle describing when it plays. */
public final class SportsItems {

    private SportsItems() {
    }

    public static ContentItem toItem(SportsEvent event, SportsSettings settings, ZoneId zone, Locale locale, Instant now) {
        String subtitle = subtitle(event, settings, zone, locale, now);
        return new ContentItem(event.itemId(), "sports", ContentKind.LIVE_EVENT, event.title(), subtitle,
                event.artwork(), List.of(), null, event.startsAt(), event.endsAt());
    }

    public static String subtitle(SportsEvent event, SportsSettings settings, ZoneId zone, Locale locale, Instant now) {
        EventPhase phase = EventPhase.of(event, now, zone);
        String when = switch (phase) {
            case LIVE -> "Live";
            case ALL_DAY_TODAY -> "Today";
            case UPCOMING_TODAY -> DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
                    .withLocale(locale).withZone(zone).format(event.startsAt());
            case ENDED -> "Ended";
            case LATER -> event.allDay()
                    ? DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(event.allDayDate())
                    : DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(locale).withZone(zone)
                            .format(event.startsAt());
        };
        String competition = settings.labelFor(event.competitionKey()).orElse("Sports");
        return when + " · " + competition;
    }
}

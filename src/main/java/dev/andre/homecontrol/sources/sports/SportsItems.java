package dev.andre.homecontrol.sources.sports;

import dev.andre.homecontrol.core.content.PinnedLinks;
import dev.andre.homecontrol.core.playback.ContentItem;
import dev.andre.homecontrol.core.playback.ContentKind;
import dev.andre.homecontrol.core.playback.PlayableRef;
import dev.andre.homecontrol.core.playback.ServiceLinks;

import java.net.URI;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Turns a sports event into a content item and the one-line subtitle describing when it plays. */
public final class SportsItems {

    private SportsItems() {
    }

    public static ContentItem toItem(SportsEvent event, SportsSettings settings, ZoneId zone, Locale locale, Instant now) {
        return toItem(event, settings, zone, locale, now, null);
    }

    public static ContentItem toItem(SportsEvent event, SportsSettings settings, ZoneId zone, Locale locale,
                                     Instant now, PinnedLinks pinnedLinks) {
        String subtitle = subtitle(event, settings, zone, locale, now);
        List<PlayableRef> playables = playables(event, settings, pinnedLinks);
        return new ContentItem(event.itemId(), "sports", ContentKind.LIVE_EVENT, event.title(), subtitle,
                event.artwork(), playables, null, event.startsAt(), event.endsAt());
    }

    /** Never depends on the event's phase: an ended event still opens the app (DAZN has replays). */
    public static List<PlayableRef> playables(SportsEvent event, SportsSettings settings, PinnedLinks pinnedLinks) {
        Optional<PlayableRef.AppLink> pinned = pinnedLinks == null
                ? Optional.empty() : pinnedLinks.linkFor("sports", event.itemId());
        if (pinned.isPresent()) {
            return List.of(pinned.get());
        }
        String provider = settings.providerFor(event.competitionKey()).orElse(null);
        Optional<URI> appHome = ServiceLinks.appHome(provider);
        return appHome.<List<PlayableRef>>map(uri -> List.of(new PlayableRef.AppLink(uri, provider))).orElse(List.of());
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
        String providerSuffix = settings.providerFor(event.competitionKey())
                .flatMap(SportsProviders::displayName)
                .map(name -> " · " + name + SportsProviders.USER_SETTING)
                .orElse("");
        return when + " · " + competition + providerSuffix;
    }
}

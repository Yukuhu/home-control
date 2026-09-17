package dev.andre.homecontrol.sources.sports;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** "Live now / Today": what is on now, then today's all-day events, then what starts later today. */
public final class LiveTodayRail {

    public static final String ID = "live-today";
    public static final String TITLE = "Live now / Today";

    private static final Set<EventPhase> SHOWN =
            EnumSet.of(EventPhase.LIVE, EventPhase.ALL_DAY_TODAY, EventPhase.UPCOMING_TODAY);

    private LiveTodayRail() {
    }

    public static List<SportsEvent> select(List<SportsEvent> events, Instant now, ZoneId zone, int limit) {
        record Ranked(EventPhase phase, SportsEvent event) {
        }
        Set<String> seen = new HashSet<>();
        return events.stream()
                .map(event -> new Ranked(EventPhase.of(event, now, zone), event))
                .filter(ranked -> SHOWN.contains(ranked.phase()))
                .sorted(Comparator.comparing(Ranked::phase)
                        .thenComparing(ranked -> ranked.event().startsAt())
                        .thenComparing(ranked -> ranked.event().title(), String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(ranked -> ranked.event().itemId()))
                .map(Ranked::event)
                .filter(event -> seen.add(event.itemId()))
                .limit(Math.max(0, limit))
                .toList();
    }
}

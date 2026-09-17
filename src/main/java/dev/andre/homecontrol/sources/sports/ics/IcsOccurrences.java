package dev.andre.homecontrol.sources.sports.ics;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Expands parsed events into concrete occurrences inside a window (plan ICS subset rules 9-13). Pure. */
public final class IcsOccurrences {

    static final int MAX_STEPS = 50_000;

    public record Result(List<IcsOccurrence> occurrences, int unsupportedRules, int unknownZones) {
        public Result {
            occurrences = List.copyOf(occurrences);
        }
    }

    private IcsOccurrences() {
    }

    public static Result expand(IcsCalendar calendar, ZoneId fallback, Instant windowStart, Instant windowEnd,
                                Duration defaultDuration) {
        Zones zones = new Zones(IcsZones.resolve(calendar.timeZone()).orElse(fallback));
        Map<String, Set<Instant>> overridden = new HashMap<>();
        for (IcsEvent event : calendar.events()) {
            if (event.recurrenceId() != null && event.uid() != null) {
                overridden.computeIfAbsent(event.uid(), uid -> new HashSet<>()).add(zones.instant(event.recurrenceId()));
            }
        }
        List<IcsOccurrence> out = new ArrayList<>();
        int unsupported = 0;
        for (IcsEvent event : calendar.events()) {
            if ("CANCELLED".equals(event.status())) {
                continue;
            }
            boolean master = event.recurrenceId() == null;
            IcsRecurrence rule = null;
            if (master && event.rrule() != null) {
                Optional<IcsRecurrence> parsed = IcsRecurrence.parse(event.rrule());
                if (parsed.isEmpty()) {
                    unsupported++;
                }
                rule = parsed.orElse(null);
            }
            Set<Instant> skip = master && event.uid() != null ? overridden.getOrDefault(event.uid(), Set.of()) : Set.of();
            new Expansion(event, rule, zones, skip, windowStart, windowEnd, defaultDuration, out).run();
        }
        out.sort(Comparator.comparing(IcsOccurrence::startsAt).thenComparing(IcsOccurrence::summary));
        return new Result(out, unsupported, zones.unknown.size());
    }

    private static final class Zones {

        private final ZoneId calendarZone;
        private final Set<String> unknown = new HashSet<>();

        Zones(ZoneId calendarZone) {
            this.calendarZone = calendarZone;
        }

        ZoneId zoneOf(IcsTime time) {
            return switch (time) {
                case IcsTime.Utc ignored -> ZoneOffset.UTC;
                case IcsTime.Date ignored -> calendarZone;
                case IcsTime.Local local -> {
                    if (local.tzid() == null) {
                        yield calendarZone;
                    }
                    Optional<ZoneId> zone = IcsZones.resolve(local.tzid());
                    if (zone.isEmpty()) {
                        unknown.add(local.tzid());
                    }
                    yield zone.orElse(calendarZone);
                }
            };
        }

        static LocalDateTime local(IcsTime time) {
            return switch (time) {
                case IcsTime.Utc utc -> LocalDateTime.ofInstant(utc.instant(), ZoneOffset.UTC);
                case IcsTime.Date date -> date.date().atStartOfDay();
                case IcsTime.Local local -> local.dateTime();
            };
        }

        Instant instant(IcsTime time) {
            return local(time).atZone(zoneOf(time)).toInstant();
        }
    }

    private static final class Expansion {

        private final IcsEvent event;
        private final IcsRecurrence rule;
        private final Set<Instant> overridden;
        private final Instant windowStart;
        private final Instant windowEnd;
        private final List<IcsOccurrence> out;
        private final ZoneId zone;
        private final LocalDateTime first;
        private final boolean allDay;
        private final long days;
        private final Duration length;
        private final Set<Instant> exInstants = new HashSet<>();
        private final Set<LocalDate> exDates = new HashSet<>();
        private int produced;

        Expansion(IcsEvent event, IcsRecurrence rule, Zones zones, Set<Instant> overridden, Instant windowStart,
                  Instant windowEnd, Duration defaultDuration, List<IcsOccurrence> out) {
            this.event = event;
            this.rule = rule;
            this.overridden = overridden;
            this.windowStart = windowStart;
            this.windowEnd = windowEnd;
            this.out = out;
            this.zone = zones.zoneOf(event.start());
            this.first = Zones.local(event.start());
            this.allDay = event.start() instanceof IcsTime.Date;
            if (allDay) {
                long d = 1;
                if (event.end() instanceof IcsTime.Date end) {
                    d = ChronoUnit.DAYS.between(first.toLocalDate(), end.date());
                } else if (event.duration() != null) {
                    d = event.duration().toDays();
                }
                this.days = Math.max(1, d);
                this.length = null;
            } else {
                Instant start = first.atZone(zone).toInstant();
                Duration l = event.end() != null ? Duration.between(start, zones.instant(event.end()))
                        : event.duration() != null ? event.duration() : defaultDuration;
                this.length = l.isZero() || l.isNegative() ? defaultDuration : l;
                this.days = 0;
            }
            for (IcsTime exdate : event.exdates()) {
                if (exdate instanceof IcsTime.Date date) {
                    exDates.add(date.date());
                } else {
                    exInstants.add(zones.instant(exdate));
                }
            }
        }

        void run() {
            produced = 1;
            emit(first);
            if (rule == null) {
                return;
            }
            if (rule.frequency() == IcsRecurrence.Frequency.WEEKLY && !rule.byDay().isEmpty()) {
                byWeekDays();
            } else {
                byFixedSteps();
            }
        }

        private void byWeekDays() {
            LocalDate weekStart = first.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            int steps = 0;
            for (long week = 0; ; week += rule.interval()) {
                for (DayOfWeek day : rule.byDay()) {
                    if (++steps > MAX_STEPS) {
                        return;
                    }
                    LocalDateTime candidate = weekStart.plusWeeks(week).plusDays(day.getValue() - 1L)
                            .atTime(first.toLocalTime());
                    if (!candidate.isAfter(first)) {
                        continue;
                    }
                    if (!accept(candidate)) {
                        return;
                    }
                }
            }
        }

        private void byFixedSteps() {
            long stepDays = rule.frequency() == IcsRecurrence.Frequency.DAILY ? rule.interval() : 7L * rule.interval();
            for (long n = 1; n <= MAX_STEPS; n++) {
                if (!accept(first.plusDays(stepDays * n))) {
                    return;
                }
            }
        }

        private boolean accept(LocalDateTime candidate) {
            if (rule.count() != null && produced >= rule.count()) {
                return false;
            }
            Instant start = candidate.atZone(zone).toInstant();
            if (rule.until() != null && pastUntil(candidate, start)) {
                return false;
            }
            if (!start.isBefore(windowEnd)) {
                return false;
            }
            produced++;
            emit(candidate);
            return true;
        }

        private boolean pastUntil(LocalDateTime candidate, Instant start) {
            return switch (rule.until()) {
                case IcsTime.Date date -> candidate.toLocalDate().isAfter(date.date());
                case IcsTime.Utc utc -> start.isAfter(utc.instant());
                case IcsTime.Local local -> candidate.isAfter(local.dateTime());
            };
        }

        private void emit(LocalDateTime occurrence) {
            Instant start = occurrence.atZone(zone).toInstant();
            if (exInstants.contains(start) || exDates.contains(occurrence.toLocalDate()) || overridden.contains(start)) {
                return;
            }
            Instant end = allDay ? occurrence.plusDays(days).atZone(zone).toInstant() : start.plus(length);
            if (!end.isAfter(windowStart) || !start.isBefore(windowEnd)) {
                return;
            }
            out.add(new IcsOccurrence(event.uid(), event.summary() == null ? "" : event.summary(), start, end,
                    allDay ? occurrence.toLocalDate() : null));
        }
    }
}

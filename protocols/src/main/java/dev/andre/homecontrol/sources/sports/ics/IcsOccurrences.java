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
    /** All of one calendar's events together: a feed of thousands of runaway rules costs this much, not each. */
    static final int MAX_STEPS_PER_CALENDAR = 250_000;

    public record Result(List<IcsOccurrence> occurrences, int unsupportedRules, int unknownZones) {
        public Result {
            occurrences = List.copyOf(occurrences);
        }
    }

    private IcsOccurrences() {
    }

    /**
     * {@code household}: the zone "today" is judged in. All-day dates are placed in it, since a date is the same
     * date wherever the calendar was kept, and it is the calendar's zone when the calendar names none.
     */
    public static Result expand(IcsCalendar calendar, ZoneId household, Instant windowStart, Instant windowEnd,
                                Duration defaultDuration) {
        Zones zones = new Zones(IcsZones.resolve(calendar.timeZone()).orElse(household), household);
        Map<String, Set<Instant>> overridden = overriddenStarts(calendar, zones);
        Pass pass = new Pass(new Window(windowStart, windowEnd));
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
            new Expansion(event, rule, zones, skip, pass, defaultDuration).run();
        }
        List<IcsOccurrence> out = pass.out();
        out.sort(Comparator.comparing(IcsOccurrence::startsAt).thenComparing(IcsOccurrence::summary));
        return new Result(out, unsupported, zones.unknown.size());
    }

    /** Per UID, the original starts that RECURRENCE-ID overrides replace. */
    private static Map<String, Set<Instant>> overriddenStarts(IcsCalendar calendar, Zones zones) {
        Map<String, Set<Instant>> overridden = new HashMap<>();
        for (IcsEvent event : calendar.events()) {
            if (event.recurrenceId() != null && event.uid() != null) {
                overridden.computeIfAbsent(event.uid(), uid -> new HashSet<>()).add(zones.instant(event.recurrenceId()));
            }
        }
        return overridden;
    }

    /** Occurrences are kept when they end after {@code start} and begin before {@code end}. */
    private record Window(Instant start, Instant end) {
    }

    /** One calendar's expansion: its window, what it found, and the steps it has left. */
    private static final class Pass {

        private final Window window;
        private final List<IcsOccurrence> out = new ArrayList<>();
        private int stepsLeft = MAX_STEPS_PER_CALENDAR;

        Pass(Window window) {
            this.window = window;
        }

        Window window() {
            return window;
        }

        List<IcsOccurrence> out() {
            return out;
        }

        /** Takes one step; false once the calendar's budget is spent. */
        boolean step() {
            return stepsLeft-- > 0;
        }
    }

    private static final class Zones {

        private final ZoneId calendarZone;
        private final ZoneId household;
        private final Set<String> unknown = new HashSet<>();

        Zones(ZoneId calendarZone, ZoneId household) {
            this.calendarZone = calendarZone;
            this.household = household;
        }

        ZoneId zoneOf(IcsTime time) {
            return switch (time) {
                case IcsTime.Utc _ -> ZoneOffset.UTC;
                case IcsTime.Date _ -> household;
                case IcsTime.Local(_, var tzid) -> {
                    if (tzid == null) {
                        yield calendarZone;
                    }
                    Optional<ZoneId> zone = IcsZones.resolve(tzid);
                    if (zone.isEmpty()) {
                        unknown.add(tzid);
                    }
                    yield zone.orElse(calendarZone);
                }
            };
        }

        static LocalDateTime local(IcsTime time) {
            return switch (time) {
                case IcsTime.Utc(var instant) -> LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
                case IcsTime.Date(var dateValue) -> dateValue.atStartOfDay();
                case IcsTime.Local(var localDateTime, _) -> localDateTime;
            };
        }

        Instant instant(IcsTime time) {
            return local(time).atZone(zoneOf(time)).toInstant();
        }

        /** A RECURRENCE-ID's place in its series; a date's is the one an all-day occurrence has on it. */
        Instant inSeries(IcsTime recurrenceId) {
            return recurrenceId instanceof IcsTime.Date(var dateValue) ? inSeries(dateValue) : instant(recurrenceId);
        }

        /** Midnight in UTC: an all-day series keeps its places when the household's zone changes. */
        static Instant inSeries(LocalDate date) {
            return date.atStartOfDay(ZoneOffset.UTC).toInstant();
        }
    }

    private static final class Expansion {

        private final IcsEvent event;
        private final IcsRecurrence rule;
        private final Set<Instant> overridden;
        private final Pass pass;
        private final ZoneId zone;
        private final LocalDateTime first;
        private final boolean allDay;
        private final long days;
        private final Duration length;
        private final Set<Instant> exInstants = new HashSet<>();
        private final Set<LocalDate> exDates = new HashSet<>();
        /** For an override: the start of the occurrence it replaces. */
        private final Instant replaces;
        private int produced;

        Expansion(IcsEvent event, IcsRecurrence rule, Zones zones, Set<Instant> overridden, Pass pass,
                  Duration defaultDuration) {
            this.event = event;
            this.rule = rule;
            this.overridden = overridden;
            this.pass = pass;
            this.zone = zones.zoneOf(event.start());
            this.first = Zones.local(event.start());
            this.replaces = event.recurrenceId() == null ? null : zones.inSeries(event.recurrenceId());
            this.allDay = event.start() instanceof IcsTime.Date;
            this.days = allDay ? allDayLength(event, first) : 0;
            this.length = allDay ? null : timedLength(event, first.atZone(zone).toInstant(), zones, defaultDuration);
            for (IcsTime exdate : event.exdates()) {
                if (exdate instanceof IcsTime.Date(var dateValue)) {
                    exDates.add(dateValue);
                } else {
                    exInstants.add(zones.instant(exdate));
                }
            }
        }

        /** Whole days an all-day event covers: up to its DTEND date, else its DURATION, and at least one. */
        private static long allDayLength(IcsEvent event, LocalDateTime first) {
            long d = 1;
            if (event.end() instanceof IcsTime.Date(var endDate)) {
                d = ChronoUnit.DAYS.between(first.toLocalDate(), endDate);
            } else if (event.duration() != null) {
                d = event.duration().toDays();
            }
            return Math.max(1, d);
        }

        /** A timed event's length: up to its DTEND, else its DURATION, else the default; never zero or negative. */
        private static Duration timedLength(IcsEvent event, Instant start, Zones zones, Duration defaultDuration) {
            Duration l;
            if (event.end() != null) {
                l = Duration.between(start, zones.instant(event.end()));
            } else if (event.duration() != null) {
                l = event.duration();
            } else {
                l = defaultDuration;
            }
            return l.isZero() || l.isNegative() ? defaultDuration : l;
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

        /** Weeks begin on the rule's WKST: with an interval, it decides which weeks are skipped. */
        private void byWeekDays() {
            DayOfWeek firstDay = rule.weekStart();
            LocalDate weekStart = first.toLocalDate().with(TemporalAdjusters.previousOrSame(firstDay));
            List<DayOfWeek> weekDays = rule.byDay().stream()
                    .sorted(Comparator.comparingLong(day -> daysInto(firstDay, day))).toList();
            int steps = 0;
            for (long week = 0; ; week += rule.interval()) {
                for (DayOfWeek day : weekDays) {
                    if (++steps > MAX_STEPS || !pass.step()) {
                        return;
                    }
                    LocalDateTime candidate = weekStart.plusWeeks(week).plusDays(daysInto(firstDay, day))
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

        /** How many days {@code day} lies after the week's first day. */
        private static long daysInto(DayOfWeek firstDay, DayOfWeek day) {
            return (day.getValue() - firstDay.getValue() + 7L) % 7;
        }

        private void byFixedSteps() {
            long stepDays = rule.frequency() == IcsRecurrence.Frequency.DAILY ? rule.interval() : 7L * rule.interval();
            for (long n = 1; n <= MAX_STEPS; n++) {
                if (!pass.step() || !accept(first.plusDays(stepDays * n))) {
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
            if (!start.isBefore(pass.window().end())) {
                return false;
            }
            produced++;
            emit(candidate);
            return true;
        }

        private boolean pastUntil(LocalDateTime candidate, Instant start) {
            return switch (rule.until()) {
                case IcsTime.Date(var dateValue) -> candidate.toLocalDate().isAfter(dateValue);
                case IcsTime.Utc(var instant) -> start.isAfter(instant);
                case IcsTime.Local(var localDateTime, _) -> candidate.isAfter(localDateTime);
            };
        }

        private void emit(LocalDateTime occurrence) {
            Instant start = occurrence.atZone(zone).toInstant();
            if (exInstants.contains(start) || exDates.contains(occurrence.toLocalDate()) || overridden.contains(start)) {
                return;
            }
            Instant end = allDay ? occurrence.plusDays(days).atZone(zone).toInstant() : start.plus(length);
            if (!end.isAfter(pass.window().start()) || !start.isBefore(pass.window().end())) {
                return;
            }
            pass.out().add(new IcsOccurrence(event.uid(), event.summary() == null ? "" : event.summary(), start, end,
                    allDay ? occurrence.toLocalDate() : null, inSeries(occurrence, start)));
        }

        private Instant inSeries(LocalDateTime occurrence, Instant start) {
            if (replaces != null) {
                return replaces;
            }
            if (rule == null) {
                return null;
            }
            return allDay ? Zones.inSeries(occurrence.toLocalDate()) : start;
        }
    }
}

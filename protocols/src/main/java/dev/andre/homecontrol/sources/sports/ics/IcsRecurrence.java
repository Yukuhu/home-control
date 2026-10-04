package dev.andre.homecontrol.sources.sports.ics;

import java.time.DayOfWeek;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The RRULE subset Home Control expands (see the plan's ICS subset rule 12). {@code weekStart} is the day weeks begin
 * on when a weekly rule with an interval counts them (WKST, Monday unless given).
 */
public record IcsRecurrence(Frequency frequency, int interval, Integer count, IcsTime until, List<DayOfWeek> byDay,
                            DayOfWeek weekStart) {

    public enum Frequency { DAILY, WEEKLY }

    private static final Map<String, DayOfWeek> DAYS = Map.of(
            "MO", DayOfWeek.MONDAY, "TU", DayOfWeek.TUESDAY, "WE", DayOfWeek.WEDNESDAY, "TH", DayOfWeek.THURSDAY,
            "FR", DayOfWeek.FRIDAY, "SA", DayOfWeek.SATURDAY, "SU", DayOfWeek.SUNDAY);

    public IcsRecurrence {
        byDay = List.copyOf(byDay);
    }

    public static Optional<IcsRecurrence> parse(String rule) {
        if (rule == null || rule.isBlank()) {
            return Optional.empty();
        }
        Parts parts = new Parts();
        for (String part : rule.strip().split(";", -1)) {
            if (!parts.read(part)) {
                return Optional.empty();
            }
        }
        return parts.recurrence();
    }

    /** The rule parts read so far; any part outside the subset rejects the whole rule. */
    private static final class Parts {

        private Frequency frequency;
        private int interval = 1;
        private Integer count;
        private IcsTime until;
        private final Set<DayOfWeek> byDay = EnumSet.noneOf(DayOfWeek.class);
        private DayOfWeek weekStart = DayOfWeek.MONDAY;

        /** Reads one {@code KEY=VALUE} part; false when it is outside the supported subset. */
        boolean read(String part) {
            int eq = part.indexOf('=');
            if (eq <= 0) {
                return false;
            }
            String key = part.substring(0, eq).strip().toUpperCase(Locale.ROOT);
            String value = part.substring(eq + 1).strip().toUpperCase(Locale.ROOT);
            try {
                return switch (key) {
                    case "FREQ" -> frequency(value);
                    case "INTERVAL" -> interval(value);
                    case "COUNT" -> count(value);
                    case "UNTIL" -> until(value);
                    case "BYDAY" -> byDay(value);
                    case "WKST" -> weekStart(value);
                    default -> false;
                };
            } catch (NumberFormatException | IcsFormatException _) {
                return false;
            }
        }

        private boolean frequency(String value) {
            if (!value.equals("DAILY") && !value.equals("WEEKLY")) {
                return false;
            }
            frequency = Frequency.valueOf(value);
            return true;
        }

        private boolean interval(String value) {
            interval = Integer.parseInt(value);
            return interval >= 1 && interval <= 1000;
        }

        private boolean count(String value) {
            count = Integer.parseInt(value);
            return count >= 1 && count <= 10_000;
        }

        private boolean until(String value) {
            until = IcsParser.parseTime(value, null);
            return true;
        }

        private boolean weekStart(String value) {
            DayOfWeek day = DAYS.get(value);
            if (day == null) {
                return false;
            }
            weekStart = day;
            return true;
        }

        private boolean byDay(String value) {
            for (String token : value.split(",")) {
                DayOfWeek day = DAYS.get(token.strip());
                if (day == null) {
                    return false;
                }
                byDay.add(day);
            }
            return true;
        }

        Optional<IcsRecurrence> recurrence() {
            if (frequency == null || (count != null && until != null)
                    || (!byDay.isEmpty() && frequency != Frequency.WEEKLY)) {
                return Optional.empty();
            }
            return Optional.of(new IcsRecurrence(frequency, interval, count, until, List.copyOf(byDay), weekStart));
        }
    }
}

package dev.andre.homecontrol.sources.sports.ics;

import java.time.DayOfWeek;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** The RRULE subset Home Control expands (see the plan's ICS subset rule 12). */
public record IcsRecurrence(Frequency frequency, int interval, Integer count, IcsTime until, List<DayOfWeek> byDay) {

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
        Frequency frequency = null;
        int interval = 1;
        Integer count = null;
        IcsTime until = null;
        Set<DayOfWeek> byDay = EnumSet.noneOf(DayOfWeek.class);
        for (String part : rule.strip().split(";", -1)) {
            int eq = part.indexOf('=');
            if (eq <= 0) {
                return Optional.empty();
            }
            String key = part.substring(0, eq).strip().toUpperCase(Locale.ROOT);
            String value = part.substring(eq + 1).strip().toUpperCase(Locale.ROOT);
            try {
                switch (key) {
                    case "FREQ" -> {
                        if (!value.equals("DAILY") && !value.equals("WEEKLY")) {
                            return Optional.empty();
                        }
                        frequency = Frequency.valueOf(value);
                    }
                    case "INTERVAL" -> {
                        interval = Integer.parseInt(value);
                        if (interval < 1 || interval > 1000) {
                            return Optional.empty();
                        }
                    }
                    case "COUNT" -> {
                        count = Integer.parseInt(value);
                        if (count < 1 || count > 10_000) {
                            return Optional.empty();
                        }
                    }
                    case "UNTIL" -> until = IcsParser.parseTime(value, null);
                    case "BYDAY" -> {
                        for (String token : value.split(",")) {
                            DayOfWeek day = DAYS.get(token.strip());
                            if (day == null) {
                                return Optional.empty();
                            }
                            byDay.add(day);
                        }
                    }
                    case "WKST" -> {
                        if (!DAYS.containsKey(value)) {
                            return Optional.empty();
                        }
                    }
                    default -> {
                        return Optional.empty();
                    }
                }
            } catch (NumberFormatException | IcsFormatException e) {
                return Optional.empty();
            }
        }
        if (frequency == null || (count != null && until != null)
                || (!byDay.isEmpty() && frequency != Frequency.WEEKLY)) {
            return Optional.empty();
        }
        return Optional.of(new IcsRecurrence(frequency, interval, count, until, List.copyOf(byDay)));
    }
}

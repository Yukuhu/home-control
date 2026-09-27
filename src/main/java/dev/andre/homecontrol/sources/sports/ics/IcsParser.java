package dev.andre.homecontrol.sources.sports.ics;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A deliberately small RFC 5545 reader: VEVENT start, end, duration, summary, uid, status and the
 * recurrence properties Home Control expands. Everything else is ignored. Pure; no I/O.
 */
public final class IcsParser {

    private static final String CALENDAR_COMPONENT = "VCALENDAR";
    private static final String VEVENT_COMPONENT = "VEVENT";

    static final int MAX_EVENTS = 5_000;
    static final String NOT_A_CALENDAR = "That link did not return a calendar (.ics)";

    private static final Pattern DATE = Pattern.compile("^\\d{8}$");
    private static final Pattern DATE_TIME = Pattern.compile("^(\\d{8})T(\\d{6})(Z?)$");
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HHmmss").withResolverStyle(ResolverStyle.STRICT);
    /** An RFC 5545 dur-value split at its first {@code T}: sign, weeks and days before it; H, M, S after it. */
    private static final Pattern DURATION_DATE = Pattern.compile("^([+-])?P(?:(\\d+)W)?(?:(\\d+)D)?$");
    private static final Pattern DURATION_TIME = Pattern.compile("^(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?$");

    record ContentLine(String name, Map<String, String> params, String value) {
    }

    private IcsParser() {
    }

    public static IcsCalendar parse(String text) {
        if (text == null) {
            throw new IcsFormatException(NOT_A_CALENDAR);
        }
        String body = text.startsWith("﻿") ? text.substring(1) : text;
        CalendarReader reader = new CalendarReader();
        for (String raw : unfold(body)) {
            ContentLine line = raw.isBlank() ? null : contentLine(raw);
            if (line != null) {
                reader.accept(line);
            }
        }
        return reader.finish();
    }

    public static List<String> unfold(String text) {
        List<String> lines = new ArrayList<>();
        StringBuilder current = null;
        for (String raw : text.split("\r\n|\n|\r", -1)) {
            if (!raw.isEmpty() && (raw.charAt(0) == ' ' || raw.charAt(0) == '\t')) {
                if (current != null) {
                    current.append(raw, 1, raw.length());
                }
                continue;
            }
            if (current != null) {
                lines.add(current.toString());
            }
            current = new StringBuilder(raw);
        }
        if (current != null) {
            lines.add(current.toString());
        }
        return lines;
    }

    static ContentLine contentLine(String raw) {
        List<Integer> semicolons = new ArrayList<>();
        int colon = valueColon(raw, semicolons);
        if (colon <= 0) {
            return null;
        }
        int nameEnd = semicolons.isEmpty() ? colon : semicolons.getFirst();
        String name = raw.substring(0, nameEnd).strip().toUpperCase(Locale.ROOT);
        if (name.isEmpty()) {
            return null;
        }
        return new ContentLine(name, params(raw, semicolons, colon), raw.substring(colon + 1));
    }

    /** The first colon outside double quotes, or -1; collects the unquoted semicolons before it. */
    private static int valueColon(String raw, List<Integer> semicolons) {
        boolean quoted = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            } else if (!quoted && c == ';') {
                semicolons.add(i);
            } else if (!quoted && c == ':') {
                return i;
            }
        }
        return -1;
    }

    private static Map<String, String> params(String raw, List<Integer> semicolons, int colon) {
        Map<String, String> params = new HashMap<>();
        for (int s = 0; s < semicolons.size(); s++) {
            int from = semicolons.get(s) + 1;
            int to = s + 1 < semicolons.size() ? semicolons.get(s + 1) : colon;
            String param = raw.substring(from, to);
            int eq = param.indexOf('=');
            if (eq > 0) {
                params.putIfAbsent(param.substring(0, eq).strip().toUpperCase(Locale.ROOT),
                        unquote(param.substring(eq + 1).strip()));
            }
        }
        return Map.copyOf(params);
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static IcsEvent event(List<ContentLine> lines) {
        String uid = null;
        String summary = null;
        String rrule = null;
        String status = null;
        IcsTime start = null;
        IcsTime end = null;
        IcsTime recurrenceId = null;
        Duration duration = null;
        List<IcsTime> exdates = new ArrayList<>();
        for (ContentLine line : lines) {
            String tzid = line.params().get("TZID");
            String value = line.value();
            // The first readable occurrence of a single-valued property wins; later ones are not parsed.
            switch (line.name()) {
                case "UID" -> uid = firstOf(uid, () -> blankToNull(unescape(value).strip()));
                case "SUMMARY" -> summary = firstOf(summary, () -> unescape(value).strip());
                case "DTSTART" -> start = firstOf(start, () -> parseTime(value, tzid));
                case "DTEND" -> end = firstOf(end, () -> parseTime(value, tzid));
                case "DURATION" -> duration = firstOf(duration, () -> parseDuration(value));
                case "RRULE" -> rrule = firstOf(rrule, value::strip);
                case "RECURRENCE-ID" -> recurrenceId = firstOf(recurrenceId, () -> parseTime(value, tzid));
                case "STATUS" -> status = value.strip().toUpperCase(Locale.ROOT);
                case "EXDATE" -> addExdates(exdates, value, tzid);
                default -> {
                    // Not part of the subset Home Control reads.
                }
            }
        }
        if (start == null) {
            throw new IcsFormatException("An event has no start");
        }
        return new IcsEvent(uid, summary, start, end, duration, rrule, exdates, recurrenceId, status);
    }

    private static <T> T firstOf(T current, Supplier<T> next) {
        return current != null ? current : next.get();
    }

    private static void addExdates(List<IcsTime> exdates, String value, String tzid) {
        for (String date : value.split(",")) {
            if (!date.isBlank()) {
                exdates.add(parseTime(date, tzid));
            }
        }
    }

    public static IcsTime parseTime(String value, String tzid) {
        String v = value == null ? "" : value.strip().toUpperCase(Locale.ROOT);
        try {
            if (DATE.matcher(v).matches()) {
                return new IcsTime.Date(LocalDate.parse(v, DateTimeFormatter.BASIC_ISO_DATE));
            }
            Matcher m = DATE_TIME.matcher(v);
            if (m.matches()) {
                LocalDateTime dateTime = LocalDateTime.of(
                        LocalDate.parse(m.group(1), DateTimeFormatter.BASIC_ISO_DATE), LocalTime.parse(m.group(2), TIME));
                return m.group(3).isEmpty()
                        ? new IcsTime.Local(dateTime, blankToNull(tzid))
                        : new IcsTime.Utc(dateTime.toInstant(ZoneOffset.UTC));
            }
        } catch (DateTimeException _) {
            throw new IcsFormatException("Unreadable date or time");
        }
        throw new IcsFormatException("Unreadable date or time");
    }

    public static Duration parseDuration(String value) {
        if (value == null) {
            return null;
        }
        String v = value.strip().toUpperCase(Locale.ROOT);
        int t = v.indexOf('T');
        Matcher date = DURATION_DATE.matcher(t < 0 ? v : v.substring(0, t));
        Matcher time = DURATION_TIME.matcher(t < 0 ? "" : v.substring(t + 1));
        if (!date.matches() || !time.matches() || "-".equals(date.group(1))) {
            return null;
        }
        try {
            Duration duration = Duration.ofDays(7 * number(date.group(2)) + number(date.group(3)))
                    .plusHours(number(time.group(1))).plusMinutes(number(time.group(2)))
                    .plusSeconds(number(time.group(3)));
            return duration.isZero() || duration.isNegative() ? null : duration;
        } catch (NumberFormatException | ArithmeticException _) {
            return null;
        }
    }

    public static String unescape(String text) {
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length()) {
                char next = text.charAt(i + 1);
                out.append(next == 'n' || next == 'N' ? '\n' : next);
                i += 2;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static long number(String digits) {
        return digits == null ? 0 : Long.parseLong(digits);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** Walks BEGIN/END nesting: the calendar's name and zone, and the VEVENTs directly inside the VCALENDAR. */
    private static final class CalendarReader {

        private final Deque<String> stack = new ArrayDeque<>();
        private final List<IcsEvent> events = new ArrayList<>();
        private List<ContentLine> current;
        private String name;
        private String zone;
        private int skipped;
        private boolean sawCalendar;

        void accept(ContentLine line) {
            switch (line.name()) {
                case "BEGIN" -> begin(line.value().strip().toUpperCase(Locale.ROOT));
                case "END" -> end();
                default -> property(line);
            }
        }

        private void begin(String component) {
            if (stack.isEmpty()) {
                if (!component.equals(CALENDAR_COMPONENT)) {
                    throw new IcsFormatException(NOT_A_CALENDAR);
                }
                sawCalendar = true;
            }
            stack.push(component);
            if (component.equals(VEVENT_COMPONENT) && stack.size() == 2) {
                current = new ArrayList<>();
            }
        }

        private void end() {
            if (stack.isEmpty()) {
                return;
            }
            String component = stack.pop();
            if (component.equals(VEVENT_COMPONENT) && stack.size() == 1 && current != null) {
                closeEvent();
            }
        }

        private void closeEvent() {
            try {
                events.add(event(current));
            } catch (IcsFormatException _) {
                skipped++;
            }
            current = null;
            if (events.size() > MAX_EVENTS) {
                throw new IcsFormatException("The calendar has more than " + MAX_EVENTS + " events");
            }
        }

        private void property(ContentLine line) {
            if (stack.size() == 1 && CALENDAR_COMPONENT.equals(stack.peek())) {
                calendarProperty(line);
            } else if (current != null && stack.size() == 2 && VEVENT_COMPONENT.equals(stack.peek())) {
                current.add(line);
            }
        }

        private void calendarProperty(ContentLine line) {
            if (line.name().equals("X-WR-CALNAME")) {
                name = unescape(line.value()).strip();
            } else if (line.name().equals("X-WR-TIMEZONE")) {
                zone = line.value().strip();
            }
        }

        IcsCalendar finish() {
            if (!sawCalendar) {
                throw new IcsFormatException(NOT_A_CALENDAR);
            }
            if (current != null) {
                skipped++;
            }
            return new IcsCalendar(blankToNull(name), blankToNull(zone), events, skipped);
        }
    }
}

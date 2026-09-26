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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A deliberately small RFC 5545 reader: VEVENT start, end, duration, summary, uid, status and the
 * recurrence properties Home Control expands. Everything else is ignored. Pure; no I/O.
 */
public final class IcsParser {

    private static final String VEVENT_COMPONENT = "VEVENT";

    static final int MAX_EVENTS = 5_000;
    static final String NOT_A_CALENDAR = "That link did not return a calendar (.ics)";

    private static final Pattern DATE = Pattern.compile("^\\d{8}$");
    private static final Pattern DATE_TIME = Pattern.compile("^(\\d{8})T(\\d{6})(Z?)$");
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HHmmss").withResolverStyle(ResolverStyle.STRICT);
    private static final Pattern DURATION = Pattern.compile(
            "^([+-])?P(?:(\\d+)W)?(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?)?$");

    record ContentLine(String name, Map<String, String> params, String value) {
    }

    private IcsParser() {
    }

    public static IcsCalendar parse(String text) {
        if (text == null) {
            throw new IcsFormatException(NOT_A_CALENDAR);
        }
        String body = text.startsWith("﻿") ? text.substring(1) : text;
        Deque<String> stack = new ArrayDeque<>();
        List<ContentLine> current = null;
        List<IcsEvent> events = new ArrayList<>();
        String name = null;
        String zone = null;
        int skipped = 0;
        boolean sawCalendar = false;
        for (String raw : unfold(body)) {
            if (raw.isBlank()) {
                continue;
            }
            ContentLine line = contentLine(raw);
            if (line == null) {
                continue;
            }
            switch (line.name()) {
                case "BEGIN" -> {
                    String component = line.value().strip().toUpperCase(Locale.ROOT);
                    if (stack.isEmpty()) {
                        if (!component.equals("VCALENDAR")) {
                            throw new IcsFormatException(NOT_A_CALENDAR);
                        }
                        sawCalendar = true;
                    }
                    stack.push(component);
                    if (component.equals(VEVENT_COMPONENT) && stack.size() == 2) {
                        current = new ArrayList<>();
                    }
                }
                case "END" -> {
                    if (stack.isEmpty()) {
                        continue;
                    }
                    String component = stack.pop();
                    if (component.equals(VEVENT_COMPONENT) && stack.size() == 1 && current != null) {
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
                }
                default -> {
                    if (stack.size() == 1 && "VCALENDAR".equals(stack.peek())) {
                        if (line.name().equals("X-WR-CALNAME")) {
                            name = unescape(line.value()).strip();
                        } else if (line.name().equals("X-WR-TIMEZONE")) {
                            zone = line.value().strip();
                        }
                    } else if (current != null && stack.size() == 2 && VEVENT_COMPONENT.equals(stack.peek())) {
                        current.add(line);
                    }
                }
            }
        }
        if (!sawCalendar) {
            throw new IcsFormatException(NOT_A_CALENDAR);
        }
        if (current != null) {
            skipped++;
        }
        return new IcsCalendar(blankToNull(name), blankToNull(zone), events, skipped);
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
        boolean quoted = false;
        int colon = -1;
        List<Integer> semicolons = new ArrayList<>();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            } else if (!quoted && c == ';') {
                semicolons.add(i);
            } else if (!quoted && c == ':') {
                colon = i;
                break;
            }
        }
        if (colon <= 0) {
            return null;
        }
        int nameEnd = semicolons.isEmpty() ? colon : semicolons.getFirst();
        String name = raw.substring(0, nameEnd).strip().toUpperCase(Locale.ROOT);
        if (name.isEmpty()) {
            return null;
        }
        Map<String, String> params = new HashMap<>();
        for (int s = 0; s < semicolons.size(); s++) {
            int from = semicolons.get(s) + 1;
            int to = s + 1 < semicolons.size() ? semicolons.get(s + 1) : colon;
            String param = raw.substring(from, to);
            int eq = param.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String value = param.substring(eq + 1).strip();
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1);
            }
            params.putIfAbsent(param.substring(0, eq).strip().toUpperCase(Locale.ROOT), value);
        }
        return new ContentLine(name, Map.copyOf(params), raw.substring(colon + 1));
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
            switch (line.name()) {
                case "UID" -> uid = uid != null ? uid : blankToNull(unescape(line.value()).strip());
                case "SUMMARY" -> summary = summary != null ? summary : unescape(line.value()).strip();
                case "DTSTART" -> start = start != null ? start : parseTime(line.value(), tzid);
                case "DTEND" -> end = end != null ? end : parseTime(line.value(), tzid);
                case "DURATION" -> duration = duration != null ? duration : parseDuration(line.value());
                case "RRULE" -> rrule = rrule != null ? rrule : line.value().strip();
                case "RECURRENCE-ID" -> recurrenceId = recurrenceId != null ? recurrenceId : parseTime(line.value(), tzid);
                case "STATUS" -> status = line.value().strip().toUpperCase(Locale.ROOT);
                case "EXDATE" -> {
                    for (String value : line.value().split(",")) {
                        if (!value.isBlank()) {
                            exdates.add(parseTime(value, tzid));
                        }
                    }
                }
                default -> {
                }
            }
        }
        if (start == null) {
            throw new IcsFormatException("An event has no start");
        }
        return new IcsEvent(uid, summary, start, end, duration, rrule, exdates, recurrenceId, status);
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
        Matcher m = DURATION.matcher(value.strip().toUpperCase(Locale.ROOT));
        if (!m.matches() || "-".equals(m.group(1))) {
            return null;
        }
        try {
            Duration duration = Duration.ofDays(7 * number(m.group(2)) + number(m.group(3)))
                    .plusHours(number(m.group(4))).plusMinutes(number(m.group(5))).plusSeconds(number(m.group(6)));
            return duration.isZero() || duration.isNegative() ? null : duration;
        } catch (NumberFormatException | ArithmeticException _) {
            return null;
        }
    }

    public static String unescape(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length()) {
                char next = text.charAt(++i);
                out.append(next == 'n' || next == 'N' ? '\n' : next);
            } else {
                out.append(c);
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
}

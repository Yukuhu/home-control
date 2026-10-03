package dev.andre.homecontrol.discovery.ssdp.protocol;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** One SSDP datagram: HTTP-style start line plus headers, no body (UPnP Device Architecture 2.0 §1). */
public record SsdpMessage(Kind kind, Map<String, String> headers) {

    public enum Kind { SEARCH_RESPONSE, NOTIFY, SEARCH_REQUEST }

    private static final Pattern MAX_AGE = Pattern.compile("max-age\\s*=\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Duration DEFAULT_MAX_AGE = Duration.ofMinutes(30);
    /** Any host on the LAN can announce any lifetime; a longer one counts as this. */
    private static final Duration LONGEST_MAX_AGE = Duration.ofDays(1);

    public static Optional<SsdpMessage> parse(byte[] data, int length) {
        if (data == null || length <= 0) {
            return Optional.empty();
        }
        String[] lines = new String(data, 0, length, StandardCharsets.UTF_8).split("\r?\n");
        String start = lines[0].trim().toUpperCase(Locale.ROOT);
        Kind kind;
        if (start.startsWith("HTTP/1.1 200")) {
            kind = Kind.SEARCH_RESPONSE;
        } else if (start.startsWith("NOTIFY * HTTP/1.1")) {
            kind = Kind.NOTIFY;
        } else if (start.startsWith("M-SEARCH * HTTP/1.1")) {
            kind = Kind.SEARCH_REQUEST;
        } else {
            return Optional.empty();
        }
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isBlank()) {
                break;
            }
            int colon = line.indexOf(':');
            if (colon > 0) {
                headers.put(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
            }
        }
        return Optional.of(new SsdpMessage(kind, Collections.unmodifiableMap(headers)));
    }

    /** A header value; blank values (such as {@code EXT:}) count as absent. */
    public Optional<String> header(String name) {
        return Optional.ofNullable(headers.get(name)).filter(value -> !value.isBlank());
    }

    /** {@code NT} for announcements, {@code ST} for search requests and responses. */
    public Optional<String> type() {
        return header(kind == Kind.NOTIFY ? "NT" : "ST");
    }

    public boolean isByeBye() {
        return kind == Kind.NOTIFY && header("NTS").map("ssdp:byebye"::equalsIgnoreCase).orElse(false);
    }

    /** The announced lifetime, at most a day, even when the number is too long for a {@code long}. */
    public Duration maxAge() {
        Matcher matcher = MAX_AGE.matcher(header("CACHE-CONTROL").orElse(""));
        if (!matcher.find()) {
            return DEFAULT_MAX_AGE;
        }
        String seconds = matcher.group(1).replaceFirst("^0+(?=\\d)", "");
        if (seconds.length() > String.valueOf(LONGEST_MAX_AGE.toSeconds()).length()) {
            return LONGEST_MAX_AGE;
        }
        Duration announced = Duration.ofSeconds(Long.parseLong(seconds));
        return announced.compareTo(LONGEST_MAX_AGE) > 0 ? LONGEST_MAX_AGE : announced;
    }

    public static byte[] search(String searchTarget, String hostHeader, int mx, String userAgent) {
        return ("M-SEARCH * HTTP/1.1\r\n"
                + "HOST: " + hostHeader + "\r\n"
                + "MAN: \"ssdp:discover\"\r\n"
                + "MX: " + mx + "\r\n"
                + "ST: " + searchTarget + "\r\n"
                + "USER-AGENT: " + userAgent + "\r\n"
                + "\r\n").getBytes(StandardCharsets.US_ASCII);
    }
}

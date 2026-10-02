package dev.andre.homecontrol.adapters.upnp.protocol;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** UPnP AVTransport times: H+:MM:SS[.F+] or H+:MM:SS.F0/F1. */
public final class UpnpTime {

    private static final Pattern TIME = Pattern.compile("^[+-]?(\\d+):(\\d{2}):(\\d{2})(?:\\.(\\d+)(?:/(\\d+))?)?$");

    private UpnpTime() {
    }

    /** Seconds, or null for absent, {@code NOT_IMPLEMENTED} or malformed values. */
    public static Double seconds(String value) {
        if (value == null) {
            return null;
        }
        Matcher m = TIME.matcher(value.strip());
        if (!m.matches() || m.group(1).length() > 9) {
            return null;
        }
        double seconds = Long.parseLong(m.group(1)) * 3600.0 + Integer.parseInt(m.group(2)) * 60.0 + Integer.parseInt(m.group(3));
        if (m.group(4) != null) {
            if (m.group(5) != null) {
                if (m.group(4).length() > 9 || m.group(5).length() > 9) {
                    return null;
                }
                long denominator = Long.parseLong(m.group(5));
                if (denominator == 0) {
                    return null;
                }
                seconds += Long.parseLong(m.group(4)) / (double) denominator;
            } else {
                seconds += Double.parseDouble("0." + m.group(4));
            }
        }
        return seconds;
    }
}

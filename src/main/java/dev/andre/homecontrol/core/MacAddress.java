package dev.andre.homecontrol.core;

import java.util.HexFormat;
import java.util.Locale;
import java.util.StringJoiner;
import java.util.regex.Pattern;

/** A MAC address as the setup page and Wake-on-LAN use it. */
public final class MacAddress {

    private static final Pattern TWELVE_HEX_DIGITS = Pattern.compile("[0-9A-Fa-f]{12}");

    private MacAddress() {
    }

    /** Accepts colons, dashes, dots or nothing between digits; returns {@code AA:BB:CC:DD:EE:FF}. */
    public static String normalize(String input) {
        if (input == null) {
            throw new IllegalArgumentException("Enter a MAC address such as A8:23:FE:01:02:03");
        }
        String hex = input.trim().replaceAll("[:\\-.\\s]", "");
        if (!TWELVE_HEX_DIGITS.matcher(hex).matches()) {
            throw new IllegalArgumentException("Not a MAC address: " + input.trim()
                    + " (expected six pairs of hex digits such as A8:23:FE:01:02:03)");
        }
        StringJoiner joined = new StringJoiner(":");
        for (int i = 0; i < 12; i += 2) {
            joined.add(hex.substring(i, i + 2).toUpperCase(Locale.ROOT));
        }
        return joined.toString();
    }

    public static byte[] bytes(String mac) {
        return HexFormat.ofDelimiter(":").parseHex(normalize(mac));
    }
}

package dev.andre.homecontrol.adapters.support;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * A duration in words, for messages and prompts: "1 minute 30 seconds", "500 milliseconds". Durations people
 * configure and are shown come in whole seconds or in milliseconds, so a fraction of a second is given in
 * milliseconds rather than rounded away.
 */
public final class DurationText {

    private DurationText() {
    }

    public static String of(Duration duration) {
        if (duration.getNano() != 0) {
            return count(duration.toMillis(), "millisecond");
        }
        if (duration.isZero()) {
            return "0 seconds";
        }
        List<String> parts = new ArrayList<>();
        addUnlessZero(parts, duration.toHours(), "hour");
        addUnlessZero(parts, duration.toMinutesPart(), "minute");
        addUnlessZero(parts, duration.toSecondsPart(), "second");
        return String.join(" ", parts);
    }

    private static void addUnlessZero(List<String> parts, long amount, String unit) {
        if (amount != 0) {
            parts.add(count(amount, unit));
        }
    }

    private static String count(long amount, String unit) {
        return amount + " " + unit + (amount == 1 ? "" : "s");
    }
}

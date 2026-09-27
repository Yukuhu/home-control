package dev.andre.homecontrol;

import com.tngtech.archunit.library.freeze.ViolationLineMatcher;

/**
 * Matches a frozen package cycle by the packages it runs through, not by the dependencies ArchUnit lists under it.
 * That list names up to 20 concrete dependencies per edge, ordered by line number, so an unrelated edit anywhere along
 * a known cycle (a comment, a new call in an allowed direction) would otherwise make the cycle look new.
 */
final class CycleViolations implements ViolationLineMatcher {

    private static final String DEPENDENCIES = "\n  1. Dependencies of";

    @Override
    public boolean matches(String stored, String actual) {
        return cycle(stored).equals(cycle(actual));
    }

    /** The "Cycle detected: Slice a -> Slice b -> Slice a" header, with its line breaks and indentation collapsed. */
    static String cycle(String violation) {
        int dependencies = violation.indexOf(DEPENDENCIES);
        String header = dependencies < 0 ? violation : violation.substring(0, dependencies);
        return header.replaceAll("\\s+", " ").strip();
    }
}

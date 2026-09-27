package dev.andre.homecontrol;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SummaryProbeTest {

    @Test
    void failsOnPurposeToProveTheSummary() {
        assertEquals("first line\nsecond line with `backticks` and @Yukuhu", "actual");
    }
}

package dev.andre.homecontrol;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CycleViolationsTest {

    private static final String ADAPTERS_DEVICE_ADAPTERS = """
            Cycle detected: Slice adapters -> \n                Slice device -> \n                Slice adapters\
            """;

    private final CycleViolations cycles = new CycleViolations();

    private static String violation(String cycle, String firstDependency, int omitted) {
        return cycle + "\n  1. Dependencies of Slice adapters\n    - " + firstDependency
                + "\n    (" + omitted + " further dependencies have been omitted...)";
    }

    @Test
    void aKnownCycleStillMatchesWhenItsListedDependenciesChange() {
        String stored = violation(ADAPTERS_DEVICE_ADAPTERS,
                "Constructor <PairingService.<init>(DeviceManager)> has parameter of type <DeviceManager> in (PairingService.java:0)", 18);
        String actual = violation(ADAPTERS_DEVICE_ADAPTERS,
                "Method <DeviceController.state(String)> calls method <DeviceManager.device(String)> in (DeviceController.java:71)", 19);

        assertThat(cycles.matches(stored, actual)).isTrue();
    }

    @Test
    void aCycleThroughOtherPackagesDoesNotMatch() {
        String stored = violation(ADAPTERS_DEVICE_ADAPTERS, "same dependency", 3);
        String actual = violation("""
                Cycle detected: Slice adapters -> \n                Slice web -> \n                Slice adapters\
                """, "same dependency", 3);

        assertThat(cycles.matches(stored, actual)).isFalse();
    }

    @Test
    void theCycleIsTheHeaderWithItsWhitespaceNormalised() {
        assertThat(CycleViolations.cycle(violation(ADAPTERS_DEVICE_ADAPTERS, "anything", 1)))
                .isEqualTo("Cycle detected: Slice adapters -> Slice device -> Slice adapters");
    }
}

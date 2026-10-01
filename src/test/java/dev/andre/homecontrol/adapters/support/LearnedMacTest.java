package dev.andre.homecontrol.adapters.support;

import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.core.WakeOnLanSettings;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class LearnedMacTest {

    private static final String REPORTED = "70:2A:D5:01:02:03";

    private final List<Map<String, String>> stored = new CopyOnWriteArrayList<>();

    private LearnedMac learnedMac(Map<String, String> settings) {
        Device tv = new Device("samsung", "Samsung TV", DeviceKind.TIZEN, "192.0.2.20", Map.of("tizen", settings),
                Instant.now());
        return new LearnedMac(() -> tv, "tizen", stored::add);
    }

    @Test
    void storesTheMacADeviceReports() {
        learnedMac(Map.of()).offer(REPORTED);

        assertThat(stored).containsExactly(Map.of(WakeOnLanSettings.MAC_ADDRESS, REPORTED));
    }

    @Test
    void replacesALearnedMacThatChanged() {
        learnedMac(Map.of(WakeOnLanSettings.MAC_ADDRESS, "70:2A:D5:09:09:09")).offer(REPORTED);

        assertThat(stored).containsExactly(Map.of(WakeOnLanSettings.MAC_ADDRESS, REPORTED));
    }

    @Test
    void storesNothingWhenTheMacIsAlreadyKnown() {
        learnedMac(Map.of(WakeOnLanSettings.MAC_ADDRESS, REPORTED)).offer(REPORTED);

        assertThat(stored).isEmpty();
    }

    @Test
    void neverReplacesAHandEnteredMac() {
        learnedMac(Map.of(WakeOnLanSettings.MAC_ADDRESS, "11:22:33:44:55:66",
                WakeOnLanSettings.MAC_ADDRESS_MANUAL, "true")).offer(REPORTED);

        assertThat(stored).isEmpty();
    }
}

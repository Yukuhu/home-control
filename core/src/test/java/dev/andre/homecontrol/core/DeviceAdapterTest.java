package dev.andre.homecontrol.core;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class DeviceAdapterTest {

    private final Device device = new Device("tv", "TV", DeviceKind.ANDROID_TV, "192.168.1.30", Map.of(),
            Instant.parse("2026-10-04T12:00:00Z"));
    private final List<Device> connected = new ArrayList<>();

    /** An adapter that implements only what every adapter must. */
    private final DeviceAdapter minimal = new DeviceAdapter() {
        @Override
        public String id() {
            return "minimal";
        }

        @Override
        public DeviceKind kind() {
            return DeviceKind.ANDROID_TV;
        }

        @Override
        public Set<Capability> capabilities(Device device) {
            return Set.of();
        }

        @Override
        public DeviceHandle connect(Device device, Consumer<DeviceState> onChange) {
            connected.add(device);
            return null;
        }
    };

    @Test
    void anAdapterThatLearnsNothingConnectsAsBefore() {
        minimal.connect(device, state -> { }, LearnedSettings.DISCARD);

        assertThat(connected).containsExactly(device);
    }

    @Test
    void anAdapterThatSaysNothingCannotTellTheForegroundApp() {
        assertThat(minimal.foregroundAppReporting(device)).isEqualTo(ForegroundAppReporting.NONE);
    }

    @Test
    void anAdapterWithoutStoredStateForgetsValidatesAndMigratesNothing() {
        assertThatCode(() -> minimal.forget(device)).doesNotThrowAnyException();
        assertThatCode(() -> minimal.validate(device)).doesNotThrowAnyException();
        assertThat(minimal.migrate(device)).isSameAs(device);
    }
}

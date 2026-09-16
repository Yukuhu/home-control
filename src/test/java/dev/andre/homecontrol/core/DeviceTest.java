package dev.andre.homecontrol.core;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DeviceTest {

    @Test
    void exposesPerAdapterSettingsAndAnEmptyMapForUnknownAdapters() {
        Device device = new Device("shield", "Shield", DeviceKind.ANDROID_TV, "10.0.0.5",
                Map.of("androidtv", Map.of("port", "6466")), Instant.EPOCH);

        assertThat(device.adapterSettings("androidtv")).containsEntry("port", "6466");
        assertThat(device.adapterSettings("cast")).isEmpty();
        assertThat(device.hasAdapter("androidtv")).isTrue();
        assertThat(device.hasAdapter("cast")).isFalse();
    }

    @Test
    void keepsAdapterOrderSoTheFirstListedAdapterIsThePrimaryOne() {
        Map<String, Map<String, String>> adapters = new LinkedHashMap<>();
        adapters.put("androidtv", Map.of());
        adapters.put("cast", Map.of());
        Device device = new Device("shield", "Shield", DeviceKind.ANDROID_TV, "10.0.0.5",
                adapters, Instant.EPOCH);

        assertThat(List.copyOf(device.adapters().keySet())).containsExactly("androidtv", "cast");
    }

    @Test
    void withAdapterAddsWithoutMutatingTheOriginal() {
        Device original = new Device("shield", "Shield", DeviceKind.ANDROID_TV, "10.0.0.5",
                Map.of(), Instant.EPOCH);

        Device extended = original.withAdapter("cast", Map.of("port", "8009"));

        assertThat(original.hasAdapter("cast")).isFalse();
        assertThat(extended.adapterSettings("cast")).containsEntry("port", "8009");
    }
}
